package ru.bscmsk.fingerly.impl.android;

import android.Manifest;
import android.annotation.TargetApi;
import android.app.KeyguardManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyPermanentlyInvalidatedException;
import android.security.keystore.KeyProperties;
import android.support.annotation.Nullable;
import android.support.v4.app.ActivityCompat;
import android.support.v4.hardware.fingerprint.FingerprintManagerCompat;
import android.support.v4.os.CancellationSignal;
import ru.bscmsk.fingerly.TouchIdCodes;
import ru.bscmsk.fingerly.impl.AbstractTouchId;
import ru.bscmsk.fingerly.interfaces.UnionTouchIdCallback;
import ru.bscmsk.fingerly.utils.AppLogger;
import ru.bscmsk.fingerly.utils.IPrefsStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.SecretKey;
import javax.crypto.spec.IvParameterSpec;
import java.io.IOException;
import java.security.*;
import java.security.cert.CertificateException;

import static android.content.Context.KEYGUARD_SERVICE;
import static ru.bscmsk.fingerly.TouchIdCodes.FINGERPRINT_ERROR_CANCELED;
import static ru.bscmsk.fingerly.TouchIdCodes.FINGERPRINT_ERROR_LOCKOUT;

/**
 * Created by mlakatkin on 01.06.2017.
 */

/**
 *
 */
public class AndroidTouchId extends AbstractTouchId {
	private static final String ANDROID_KEY_STORE_NAME = "AndroidKeyStore";
	private static final String TOUCH_ID_KEY = "DefaultKeyName";
	private final int LOCKOUT_TIMEOUT = 35000;
	private KeyStore mKeyStore;
	private KeyGenerator mKeyGenerator;
	private CancellationSignal cancellationSignal;
	private byte[] IV  = "iv salt for 1234".getBytes();

	public AndroidTouchId(Context context, IPrefsStore prefs) {
		super(context, prefs);
	}

	@Override
	protected void initTouchId() {
		initKeyGen();
		initKeyStorage();
	}

	@Override
	@TargetApi(Build.VERSION_CODES.M)
	public boolean isApiSupported() {
		if ((Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) && isPermissionGranted()) {
			boolean detectHardware = detectHardware();
			KeyguardManager keyguardManager = (KeyguardManager) context.getSystemService(KEYGUARD_SERVICE);
			return detectHardware
				&& keyguardManager != null
				&& keyguardManager.isDeviceSecure();
		}
		return false;
	}

	private boolean detectHardware() {
		FingerprintManagerCompat fingerprintManager = FingerprintManagerCompat.from(context);
		return fingerprintManager.isHardwareDetected();
	}

	@Override
	@TargetApi(Build.VERSION_CODES.M)
	public boolean isPermissionGranted() {
		return ActivityCompat.checkSelfPermission(context, Manifest.permission.USE_FINGERPRINT) == PackageManager.PERMISSION_GRANTED;
	}

	@Override
	public void requestFinger(CipherMode mode, UnionTouchIdCallback callback) {
		this.cancellationSignal = new CancellationSignal();
		AppLogger.d(this, "requestFinger");
		FingerprintManagerCompat fingerprintManager = FingerprintManagerCompat.from(context);
		FingerprintManagerCompat.CryptoObject crypto = new FingerprintManagerCompat.CryptoObject(initCipher(mode));
		fingerprintManager.authenticate(crypto, 0, cancellationSignal, getInternalCallBack(callback), null);
	}

	@Override
	public void cancel() {
		if (cancellationSignal != null && !cancellationSignal.isCanceled())
			cancellationSignal.cancel();
	}

	@Override
	public int getLockoutTimeout() {
		return LOCKOUT_TIMEOUT;
	}

	@Override
	public String permissionCode() {
		return Manifest.permission.USE_FINGERPRINT;
	}

	@Override
	public boolean isKeyPermanentlyInvalidated() {
		return !hasFingerPrints() || (isPermissionGranted() && initCipher(CipherMode.DECRYPT) == null);
	}

	@Override
	@TargetApi(Build.VERSION_CODES.M)
	public boolean hasFingerPrints() {
		if ((Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) && isPermissionGranted()) {
			FingerprintManagerCompat fingerprintManager = FingerprintManagerCompat.from(context);
			return fingerprintManager.hasEnrolledFingerprints();
		}
		return false;
	}

	@Override
	public void clearKeystore() {
		if (mKeyGenerator == null)
			return;

		try {
			mKeyStore.load(null);
			mKeyStore.deleteEntry(TOUCH_ID_KEY);
		} catch (IOException
			| NoSuchAlgorithmException
			| CertificateException
			| KeyStoreException e) {
			AppLogger.e(this, "clearKeystore", e);
		}
	}

	private void initKeyStorage() {
		try {
			mKeyStore = KeyStore.getInstance(ANDROID_KEY_STORE_NAME);
		} catch (KeyStoreException e) {
			AppLogger.d(this, "Failed to get an instance of KeyStore", e);
		}
	}

	@TargetApi(Build.VERSION_CODES.M)
	private void initKeyGen() {
		try {
			mKeyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE_NAME);
		} catch (NoSuchAlgorithmException | NoSuchProviderException e) {
			AppLogger.d(this, "Failed to get an instance of KeyGenerator", e);
		}
	}

	@Nullable
	@TargetApi(Build.VERSION_CODES.M)
	private Cipher initCipher(CipherMode mode) {
		Cipher mCipher;
		try {
			String transformation = KeyProperties.KEY_ALGORITHM_AES + "/"
				+ KeyProperties.BLOCK_MODE_CBC + "/"
				+ KeyProperties.ENCRYPTION_PADDING_PKCS7;
			mCipher = Cipher.getInstance(transformation);
		} catch (NoSuchAlgorithmException |
			NoSuchPaddingException e) {
			AppLogger.e(this, "Failed to init Cipher", e);
			return null;
		}
		try {
			mKeyStore.load(null);
			SecretKey key = (SecretKey) mKeyStore.getKey(TOUCH_ID_KEY, null);
			if (key == null)
				return null;
			if (mode == CipherMode.ENCRYPT) {
				mCipher.init(Cipher.ENCRYPT_MODE, key, new IvParameterSpec(IV));
			} else {
				mCipher.init(Cipher.DECRYPT_MODE, key, new IvParameterSpec(IV));
			}
		} catch (KeyPermanentlyInvalidatedException e) {
			AppLogger.e(this, "KeyPermanentlyInvalidatedException ", e);
			return null;
		} catch (KeyStoreException
			| UnrecoverableKeyException
			| CertificateException
			| NoSuchAlgorithmException
			| IOException
			| InvalidAlgorithmParameterException
			| InvalidKeyException e) {
			AppLogger.e(this, "Failed to init Cipher", e);
			return null;
		}
		return mCipher;
	}

	private FingerprintManagerCompat.AuthenticationCallback getInternalCallBack(final UnionTouchIdCallback callback) {
		return new FingerprintManagerCompat.AuthenticationCallback() {
			@Override
			public void onAuthenticationError(int errMsgId, CharSequence errString) {
				super.onAuthenticationError(errMsgId, errString);
				if (errMsgId != FINGERPRINT_ERROR_CANCELED && errMsgId != FINGERPRINT_ERROR_LOCKOUT)
					makeVibrate();
				callback.onAuthenticationError(errMsgId, errString);
			}

			@Override
			public void onAuthenticationHelp(int helpMsgId, CharSequence helpString) {
				super.onAuthenticationHelp(helpMsgId, helpString);
				makeVibrate();
				callback.onAuthenticationHelp(helpMsgId, helpString);
			}

			@Override
			public void onAuthenticationSucceeded(FingerprintManagerCompat.AuthenticationResult result) {
				super.onAuthenticationSucceeded(result);
				makeVibrate();
				if(result.getCryptoObject() == null || result.getCryptoObject().getCipher() == null) {
					callback.onAuthenticationError(TouchIdCodes.FINGERPRINT_ERROR_NO_SPACE, "The keystore was compromised");
					return;
				}

				callback.onAuthenticationSucceeded(result.getCryptoObject().getCipher());
			}

			@Override
			public void onAuthenticationFailed() {
				super.onAuthenticationFailed();
				makeVibrate();
				callback.onAuthenticationError(-1, "Authentication failed");
			}
		};
	}

	@TargetApi(Build.VERSION_CODES.M)
	@Override
	public void createKey() {
		if (mKeyStore == null)
			return;
		try {
			mKeyStore.load(null);
			generateKey(TOUCH_ID_KEY, true);
		} catch (KeyStoreException
			| NoSuchAlgorithmException
			| InvalidAlgorithmParameterException
			| CertificateException
			| IOException e) {
			AppLogger.e(this, "createKey exception", e);
		}
	}

	@TargetApi(Build.VERSION_CODES.M)
	private void generateKey(String keyName, boolean invalidatedByBiometricEnrollment)
		throws InvalidAlgorithmParameterException, KeyStoreException {
		if (mKeyStore.containsAlias(keyName)) return;
		// Set the alias of the entry in Android KeyStore where the key will appear
		// and the constrains (purposes) in the constructor of the Builder
		KeyGenParameterSpec.Builder builder
			= new KeyGenParameterSpec.Builder(keyName, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
			.setBlockModes(KeyProperties.BLOCK_MODE_CBC)
			// Require the user to authenticate with a fingerprint to authorize every use
			// of the key
			.setUserAuthenticationRequired(true)
			.setRandomizedEncryptionRequired(false)
			.setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_PKCS7);
		// This is a workaround to avoid crashes on devices whose API level is < 24
		// because KeyGenParameterSpec.Builder#setInvalidatedByBiometricEnrollment is only
		// visible on API level +24.
		// Ideally there should be a compat library for KeyGenParameterSpec.Builder but
		// which isn't available yet.
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N)
			builder.setInvalidatedByBiometricEnrollment(invalidatedByBiometricEnrollment);

		mKeyGenerator.init(builder.build());
		mKeyGenerator.generateKey();
	}
}
