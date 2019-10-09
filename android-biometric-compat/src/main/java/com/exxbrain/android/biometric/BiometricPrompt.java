/*
 * Copyright 2018 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.exxbrain.android.biometric;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.DialogInterface;
import android.os.Build;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.Handler;
import android.support.annotation.IntDef;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v4.app.Fragment;
import android.support.v4.app.FragmentActivity;
import android.support.v4.app.FragmentManager;
import android.support.v4.hardware.fingerprint.FingerprintManagerCompat;
import android.util.Log;

import java.lang.annotation.Retention;
import java.security.Signature;
import java.util.concurrent.Executor;

import javax.crypto.Cipher;
import javax.crypto.Mac;

import static android.content.ContentValues.TAG;
import static java.lang.annotation.RetentionPolicy.SOURCE;

/**
 * A class that manages a system-provided biometric prompt. On devices running P and above, this
 * will show a system-provided authentication prompt, using a device's supported biometric
 * (fingerprint, iris, face, etc). On devices before P, this will show a dialog prompting for
 * fingerprint authentication. The prompt will persist across configuration changes unless
 * explicitly canceled by the client. For security reasons, the prompt will automatically dismiss
 * when the application is no longer in the foreground.
 *
 * To persist authentication across configuration changes, developers should (re)create the
 * BiometricPrompt every time the activity/fragment is created. Instantiating the library with a new
 * callback early in the fragment/activity lifecycle (e.g. onCreate) allows the ongoing authenticate
 * session's callbacks to be received by the new fragment/activity. Note that
 * {@link BiometricPrompt#cancelAuthentication()} should not be called, and
 * {@link BiometricPrompt#authenticate(PromptInfo)} or
 * {@link BiometricPrompt#authenticate(PromptInfo, CryptoObject)} does not need to be invoked after
 * the new activity/fragment is created, since we are keeping/continuing the same session.
 */
@SuppressLint("SyntheticAccessor")
public class BiometricPrompt implements BiometricConstants {

    // In order to keep consistent behavior between versions, we need to send
    // FingerprintDialogFragment a message indicating whether or not to dismiss the UI instantly.
    private static final int DELAY_MILLIS = 500;

    static final String DIALOG_FRAGMENT_TAG = "FingerprintDialogFragment";
    static final String FINGERPRINT_HELPER_FRAGMENT_TAG = "FingerprintHelperFragment";
    static final String KEY_TITLE = "title";
    static final String KEY_SUBTITLE = "subtitle";
    static final String KEY_DESCRIPTION = "description";
    static final String KEY_NEGATIVE_TEXT = "negative_text";
    static final String KEY_REQUIRE_CONFIRMATION = "require_confirmation";
    static final String KEY_ALLOW_DEVICE_CREDENTIAL = "allow_device_credential";

    @Retention(SOURCE)
    @IntDef({ERROR_HW_UNAVAILABLE,
            ERROR_UNABLE_TO_PROCESS,
            ERROR_TIMEOUT,
            ERROR_NO_SPACE,
            ERROR_CANCELED,
            ERROR_LOCKOUT,
            ERROR_VENDOR,
            ERROR_LOCKOUT_PERMANENT,
            ERROR_USER_CANCELED,
            ERROR_NO_BIOMETRICS,
            ERROR_HW_NOT_PRESENT,
            ERROR_NEGATIVE_BUTTON,
            ERROR_NO_DEVICE_CREDENTIAL})
    private @interface BiometricError {
    }

    public static class AuthenticationResult {
        private final CryptoObject mCryptoObject;

        AuthenticationResult(CryptoObject cryptoObject) {
            this.mCryptoObject = cryptoObject;
        }

        /**
         * Obtain the crypto object associated with this transaction
         *
         * @return crypto object provided to {@link #authenticate(PromptInfo, CryptoObject)}.
         */
        @Nullable
        CryptoObject getCryptoObject() {
            return this.mCryptoObject;
        }
    }

    static class CryptoObject {

        private android.hardware.biometrics.BiometricPrompt.CryptoObject biometricCryptoObject;
        private FingerprintManagerCompat.CryptoObject fingerprintCryptoObject;

        CryptoObject(Cipher cipher) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                biometricCryptoObject = new android.hardware.biometrics.BiometricPrompt.CryptoObject(cipher);
            } else {
                fingerprintCryptoObject = new FingerprintManagerCompat.CryptoObject(cipher);
            }
        }

        CryptoObject(Signature signature) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                biometricCryptoObject = new android.hardware.biometrics.BiometricPrompt.CryptoObject(signature);
            } else {
                fingerprintCryptoObject = new FingerprintManagerCompat.CryptoObject(signature);
            }
        }

        CryptoObject(Mac mac) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                biometricCryptoObject = new android.hardware.biometrics.BiometricPrompt.CryptoObject(mac);
            } else {
                fingerprintCryptoObject = new FingerprintManagerCompat.CryptoObject(mac);
            }
        }

        CryptoObject(android.hardware.biometrics.BiometricPrompt.AuthenticationResult result) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                biometricCryptoObject = result.getCryptoObject();
            }
        }

        Cipher getCipher() {
            if (this.biometricCryptoObject == null) {
                return this.fingerprintCryptoObject.getCipher();
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                return biometricCryptoObject.getCipher();
            }
            return null;
        }

        Signature getSignature() {
            if (this.biometricCryptoObject == null) {
                return this.fingerprintCryptoObject.getSignature();
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                return biometricCryptoObject.getSignature();
            }
            return null;
        }

        Mac getMac() {
            if (this.biometricCryptoObject == null) {
                return this.fingerprintCryptoObject.getMac();
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                return biometricCryptoObject.getMac();
            }
            return null;
        }
    }

    /**
     * Callback structure provided to {@link BiometricPrompt}. Users of {@link
     * BiometricPrompt} must provide an implementation of this for listening to
     * fingerprint events.
     */
    public abstract static class AuthenticationCallback {
        /**
         * Called when an unrecoverable error has been encountered and the operation is complete.
         * No further actions will be made on this object.
         *
         * @param errorCode An integer identifying the error message. The error message will usually
         *                  be one of the BIOMETRIC_ERROR constants.
         * @param errString A human-readable error string that can be shown on an UI
         */
        public void onAuthenticationError(@BiometricError int errorCode, @NonNull CharSequence errString) {
        }

        /**
         * Called when a biometric is recognized.
         *
         * @param result An object containing authentication-related data
         */
        public void onAuthenticationSucceeded(@NonNull AuthenticationResult result) {
        }

        /**
         * Called when a biometric is valid but not recognized.
         */

        public void onAuthenticationFailed() {
        }
    }

    /**
     * A class that contains a builder which returns the {@link PromptInfo} to be used in
     * {@link #authenticate(PromptInfo, CryptoObject)} and {@link #authenticate(PromptInfo)}.
     */
    public static class PromptInfo {

        /**
         * A builder that collects arguments to be shown on the system-provided biometric dialog.
         */
        public static class Builder
        {

            private final Bundle mBundle = new Bundle();

            /**
             * @param title title
             * Required: Set the title to display.
             * @return Builder
             */
            @NonNull
            public Builder setTitle(@NonNull CharSequence title) {
                mBundle.putCharSequence(KEY_TITLE, title);
                return this;
            }

            /**
             * @param subtitle sutitle
             * Optional: Set the subtitle to display.
             * @return Builder
             */
            @NonNull
            public Builder setSubtitle(@Nullable CharSequence subtitle) {
                mBundle.putCharSequence(KEY_SUBTITLE, subtitle);
                return this;
            }

            /**
             * Optional: Set the description to display.
             */
            @NonNull
            public Builder setDescription(@Nullable CharSequence description) {
                mBundle.putCharSequence(KEY_DESCRIPTION, description);
                return this;
            }

            /**
             * Required: Set the text for the negative button. This would typically be used as a
             * "Cancel" button, but may be also used to show an alternative method for
             * authentication, such as screen that asks for a backup password.
             */
            @NonNull
            public Builder setNegativeButtonText(@NonNull CharSequence text) {
                mBundle.putCharSequence(KEY_NEGATIVE_TEXT, text);
                return this;
            }

            /**
             * Optional: A hint to the system to require user confirmation after a biometric has
             * been authenticated. For example, implicit modalities like Face and
             * Iris authentication are passive, meaning they don't require an explicit user action
             * to complete. When set to 'false', the user action (e.g. pressing a button)
             * will not be required. BiometricPrompt will require confirmation by default.
             *
             * A typical use case for not requiring confirmation would be for low-risk transactions,
             * such as re-authenticating a recently authenticated application. A typical use case
             * for requiring confirmation would be for authorizing a purchase.
             *
             * Note that this is a hint to the system. The system may choose to ignore the flag. For
             * example, if the user disables implicit authentication in Settings, or if it does not
             * apply to a modality (e.g. Fingerprint). When ignored, the system will default to
             * requiring confirmation.
             *
             * This method only applies to Q and above.
             */
            @NonNull
            public Builder setConfirmationRequired(boolean requireConfirmation) {
                mBundle.putBoolean(KEY_REQUIRE_CONFIRMATION, requireConfirmation);
                return this;
            }

            /**
             * The user will first be prompted to authenticate with biometrics, but also given the
             * option to authenticate with their device PIN, pattern, or password. Developers should
             * first check {@link android.app.KeyguardManager#isDeviceSecure()} before enabling
             * this. If the device is not secure, {@link BiometricPrompt#ERROR_NO_DEVICE_CREDENTIAL}
             * will be returned in
             * {@link AuthenticationCallback#onAuthenticationError(int, CharSequence)}.
             *
             * <p>Note that {@link Builder#setNegativeButtonText(CharSequence)} should not be set
             * if this is set to true.
             *
             * <p>On versions P and below, once the device credential prompt is shown,
             * {@link #cancelAuthentication()} will not work, since the library internally launches
             * {@link android.app.KeyguardManager#createConfirmDeviceCredentialIntent(CharSequence,
             * CharSequence)}, which does not have a public API for cancellation.
             *
             * @param enable When true, the prompt will fall back to ask for the user's device
             *               credentials (PIN, pattern, or password).
             */
            @NonNull
            public Builder setDeviceCredentialAllowed(boolean enable) {
                mBundle.putBoolean(KEY_ALLOW_DEVICE_CREDENTIAL, enable);
                return this;
            }

            /**
             * Creates a {@link BiometricPrompt}.
             *
             * @return a {@link BiometricPrompt}
             * @throws IllegalArgumentException if any of the required fields are not set.
             */
            @NonNull
            public PromptInfo build() {
                return new PromptInfo(mBundle);
            }
        }

        private Bundle mBundle;

        PromptInfo(Bundle bundle) {
            mBundle = bundle;
        }

        Bundle getBundle() {
            return mBundle;
        }

        /**
         * @return See {@link Builder#setTitle(CharSequence)}.
         */
        @NonNull
        public CharSequence getTitle() {
            return mBundle.getCharSequence(KEY_TITLE);
        }

        /**
         * @return See {@link Builder#setSubtitle(CharSequence)}.
         */
        @Nullable
        public CharSequence getSubtitle() {
            return mBundle.getCharSequence(KEY_SUBTITLE);
        }

        /**
         * @return See {@link Builder#setDescription(CharSequence)}.
         */
        @Nullable
        public CharSequence getDescription() {
            return mBundle.getCharSequence(KEY_DESCRIPTION);
        }

        /**
         * @return See {@link Builder#setNegativeButtonText(CharSequence)}.
         */
        @NonNull
        public CharSequence getNegativeButtonText() {
            return mBundle.getCharSequence(KEY_NEGATIVE_TEXT);
        }

        /**
         * @return See {@link Builder#setConfirmationRequired(boolean)}.
         */
        public boolean isConfirmationRequired() {
            return mBundle.getBoolean(KEY_REQUIRE_CONFIRMATION);
        }

        /**
         * @return See {@link Builder#setDeviceCredentialAllowed(boolean)}.
         */
        public boolean isDeviceCredentialAllowed() {
            return mBundle.getBoolean(KEY_ALLOW_DEVICE_CREDENTIAL);
        }
    }

    // Passed in from the client.
    private FragmentActivity mFragmentActivity;
    private Fragment mFragment;
    private final Executor mExecutor;
    private final AuthenticationCallback mAuthenticationCallback;
    private CancellationSignal mCancellationSignal = new CancellationSignal();

    // Created internally for devices before P.
    private FingerprintDialogFragment mFingerprintDialogFragment;
    private FingerprintHelperFragment mFingerprintHelperFragment;

    // Created internally for devices P and above.
//    private BiometricFragment mBiometricFragment;

    // In Q, we must ignore the first onPause if setDeviceCredentialAllowed is true, since
    // the Q implementation launches ConfirmDeviceCredentialActivity which is an activity and
    // puts the client app onPause.
    private boolean mPausedOnce;

    // Whether this prompt is being hosted in DeviceCredentialHandlerActivity.
    private boolean mIsHandlingDeviceCredential;

    private PromptInfo mInfo;

    /**
     * A shim to interface with the framework API and simplify the support library's API.
     * The support library sends onAuthenticationError when the negative button is pressed.
     * Conveniently, the {@link FingerprintDialogFragment} also uses the
     * {@link DialogInterface.OnClickListener} for its buttons ;)
     */
    private final DialogInterface.OnClickListener mNegativeButtonListener =
            new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    mExecutor.execute(new Runnable() {
                        @Override
                        public void run() {
                            if (usingBiometricFragment()) {
                                final CharSequence errorText =
                                        mInfo.getNegativeButtonText();
                                mAuthenticationCallback.onAuthenticationError(
                                        ERROR_NEGATIVE_BUTTON, errorText != null ? errorText : "");
                            } else {
                                final CharSequence errorText =
                                        mFingerprintDialogFragment.getNegativeButtonText();
                                mAuthenticationCallback.onAuthenticationError(
                                        ERROR_NEGATIVE_BUTTON, errorText != null ? errorText : "");
                                mFingerprintHelperFragment.cancel(
                                        FingerprintHelperFragment
                                                .USER_CANCELED_FROM_NEGATIVE_BUTTON);
                            }
                        }
                    });
                }
            };

    /**
     * Constructs a {@link BiometricPrompt} which can be used to prompt the user for
     * authentication. The authentication prompt created by
     * {@link BiometricPrompt#authenticate(PromptInfo, CryptoObject)} and
     * {@link BiometricPrompt#authenticate(PromptInfo)} will persist across device
     * configuration changes by default. If authentication is in progress, re-creating
     * the {@link BiometricPrompt} can be used to update the {@link Executor} and
     * {@link AuthenticationCallback}. This should be used to update the
     * {@link AuthenticationCallback} after configuration changes.
     *
     * @param fragmentActivity A reference to the client's activity.
     * @param executor         An executor to handle callback events.
     * @param callback         An object to receive authentication events.
     */
    @SuppressLint("LambdaLast")
    public BiometricPrompt(@NonNull FragmentActivity fragmentActivity,
            @NonNull Executor executor, @NonNull AuthenticationCallback callback) {

        if (fragmentActivity == null) {
            throw new IllegalArgumentException("FragmentActivity must not be null");
        }
        if (executor == null) {
            throw new IllegalArgumentException("Executor must not be null");
        }
        if (callback == null) {
            throw new IllegalArgumentException("AuthenticationCallback must not be null");
        }
        mFragmentActivity = fragmentActivity;
        mAuthenticationCallback = callback;
        mExecutor = executor;
    }

    /**
     * Constructs a {@link BiometricPrompt} which can be used to prompt the user for
     * authentication. The authenticaton prompt created by
     * {@link BiometricPrompt#authenticate(PromptInfo, CryptoObject)} and
     * {@link BiometricPrompt#authenticate(PromptInfo)} will persist across device
     * configuration changes by default. If authentication is in progress, re-creating
     * the {@link BiometricPrompt} can be used to update the {@link Executor} and
     * {@link AuthenticationCallback}. This should be used to update the
     * {@link AuthenticationCallback} after configuration changes.
     * such as {@link Fragment#onCreate(Bundle)}.
     *
     * @param fragment A reference to the client's fragment.
     * @param executor An executor to handle callback events.
     * @param callback An object to receive authentication events.
     */
    @SuppressLint("LambdaLast")
    public BiometricPrompt(@NonNull Fragment fragment,
            @NonNull Executor executor, @NonNull AuthenticationCallback callback) {
        if (fragment == null) {
            throw new IllegalArgumentException("FragmentActivity must not be null");
        }
        if (executor == null) {
            throw new IllegalArgumentException("Executor must not be null");
        }
        if (callback == null) {
            throw new IllegalArgumentException("AuthenticationCallback must not be null");
        }
        mFragment = fragment;
        mAuthenticationCallback = callback;
        mExecutor = executor;
    }

    /**
     * Shows the biometric prompt. The prompt survives lifecycle changes by default. To cancel the
     * authentication, use {@link #cancelAuthentication()}.
     *
     * @param info   The information that will be displayed on the prompt. Create this object using
     *               {@link PromptInfo.Builder}.
     * @param crypto The crypto object associated with the authentication.
     */
    public void authenticate(@NonNull PromptInfo info, @NonNull CryptoObject crypto) {
        authenticateInternal(info, crypto);
    }

    /**
     * Shows the biometric prompt. The prompt survives lifecycle changes by default. To cancel the
     * authentication, use {@link #cancelAuthentication()}.
     *
     * @param info The information that will be displayed on the prompt. Create this object using
     *             {@link PromptInfo.Builder}.
     */
    public void authenticate(@NonNull PromptInfo info) {
        authenticateInternal(info, null /* crypto */);
    }

    private void authenticateInternal(@NonNull PromptInfo info, @Nullable CryptoObject crypto) {

        // Don't launch prompt if state has already been saved (potential for state loss).
        final FragmentManager fragmentManager = getFragmentManager();
        if (fragmentManager.isStateSaved()) {
            Log.w(TAG, "Not launching prompt. authenticate() called after onSaveInstanceState()");
            return;
        }

        mInfo = info;

        if (usingBiometricFragment()) {
            Context context = mFragmentActivity != null ? mFragmentActivity : mFragment.getActivity();
            android.hardware.biometrics.BiometricPrompt.Builder promptBuilder =
                    new android.hardware.biometrics.BiometricPrompt.Builder(context)
                            .setTitle(info.getTitle())
                            .setSubtitle(info.getSubtitle())
                            .setDescription(info.getDescription())
                            .setNegativeButton(info.getNegativeButtonText(), mExecutor, mNegativeButtonListener);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                promptBuilder
                        .setDeviceCredentialAllowed(info.isDeviceCredentialAllowed())
                        .setConfirmationRequired(info.isConfirmationRequired());
            }

            android.hardware.biometrics.BiometricPrompt prompt = promptBuilder.build();

            android.hardware.biometrics.BiometricPrompt.AuthenticationCallback callback = new android.hardware.biometrics.BiometricPrompt.AuthenticationCallback() {
                @Override
                public void onAuthenticationError(int errorCode, CharSequence errString) {
                    super.onAuthenticationError(errorCode, errString);
                    mAuthenticationCallback.onAuthenticationError(errorCode, errString);
                }

                @Override
                public void onAuthenticationHelp(int helpCode, CharSequence helpString) {
                    super.onAuthenticationHelp(helpCode, helpString);
                }

                @Override
                public void onAuthenticationSucceeded(android.hardware.biometrics.BiometricPrompt.AuthenticationResult result) {
                    super.onAuthenticationSucceeded(result);
                    mAuthenticationCallback.onAuthenticationSucceeded(new AuthenticationResult(new CryptoObject(result)));
                }

                @Override
                public void onAuthenticationFailed() {
                    super.onAuthenticationFailed();
                    mAuthenticationCallback.onAuthenticationFailed();
                }
            };



            if (crypto != null) {
                prompt.authenticate(crypto.biometricCryptoObject, mCancellationSignal, mExecutor, callback);
            } else {
                prompt.authenticate(mCancellationSignal, mExecutor, callback);
            }
        } else {
            // Create the UI
            FingerprintDialogFragment fingerprintDialogFragment =
                    (FingerprintDialogFragment) fragmentManager.findFragmentByTag(
                            DIALOG_FRAGMENT_TAG);
            if (fingerprintDialogFragment != null) {
                mFingerprintDialogFragment = fingerprintDialogFragment;
            } else {
                mFingerprintDialogFragment = FingerprintDialogFragment.newInstance();
            }

            mFingerprintDialogFragment.setNegativeButtonListener(mNegativeButtonListener);

            mFingerprintDialogFragment.setBundle(info.getBundle());
            if (fingerprintDialogFragment == null) {
                mFingerprintDialogFragment.show(fragmentManager, DIALOG_FRAGMENT_TAG);
            } else if (mFingerprintDialogFragment.isDetached()) {
                fragmentManager.beginTransaction().attach(mFingerprintDialogFragment)
                        .commitAllowingStateLoss();
            }

            // Create the connection to FingerprintManager
            FingerprintHelperFragment fingerprintHelperFragment =
                    (FingerprintHelperFragment) fragmentManager.findFragmentByTag(
                            FINGERPRINT_HELPER_FRAGMENT_TAG);
            if (fingerprintHelperFragment != null) {
                mFingerprintHelperFragment = fingerprintHelperFragment;
            } else {
                mFingerprintHelperFragment = FingerprintHelperFragment.newInstance();
            }

            mFingerprintHelperFragment.setCallback(mExecutor, mAuthenticationCallback);
            final Handler fingerprintDialogHandler = mFingerprintDialogFragment.getHandler();
            mFingerprintHelperFragment.setHandler(fingerprintDialogHandler);
            mFingerprintHelperFragment.setCryptoObject(crypto);
            fingerprintDialogHandler.sendMessageDelayed(
                    fingerprintDialogHandler.obtainMessage(
                            FingerprintDialogFragment.DISPLAYED_FOR_500_MS), DELAY_MILLIS);

            if (fingerprintHelperFragment == null) {
                // If the fragment hasn't been added before, add it. It will also start the
                // authentication.
                fragmentManager.beginTransaction()
                        .add(mFingerprintHelperFragment, FINGERPRINT_HELPER_FRAGMENT_TAG)
                        .commitAllowingStateLoss();
            } else if (mFingerprintHelperFragment.isDetached()) {
                // If it's been added before, just re-attach it.
                fragmentManager.beginTransaction().attach(mFingerprintHelperFragment)
                        .commitAllowingStateLoss();
            }
        }

        // For the case when onResume() is being called right after authenticate,
        // we need to make sure that all fragment transactions have been committed.
        fragmentManager.executePendingTransactions();
    }

    /**
     * Cancels the biometric authentication, and dismisses the dialog upon confirmation from the
     * biometric service.
     */
    public void cancelAuthentication() {
        if (usingBiometricFragment()) {
            // It does not work
        } else {
            if (mFingerprintHelperFragment != null && mFingerprintDialogFragment != null) {
                dismissFingerprintFragments(mFingerprintDialogFragment, mFingerprintHelperFragment);
            }
        }
    }

    /**
     * Gets the appropriate fragment manager for the client. This is either the support fragment
     * manager for a client activity or the child fragment manager for a client fragment.
     */
    private FragmentManager getFragmentManager() {
        return mFragmentActivity != null ? mFragmentActivity.getSupportFragmentManager()
                : mFragment.getChildFragmentManager();
    }

    /**
     * @return True if the prompt handles authentication via BiometricFragment, or false
     * if it does so via {@link FingerprintDialogFragment}.
     */
    private static boolean usingBiometricFragment() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.P;
    }

    /**
     * Dismisses the given {@link FingerprintDialogFragment} and {@link FingerprintHelperFragment},
     * both of which must be non-null.
     */
    private static void dismissFingerprintFragments(
            @NonNull FingerprintDialogFragment fingerprintDialogFragment,
            @NonNull FingerprintHelperFragment fingerprintHelperFragment) {
        fingerprintDialogFragment.dismissSafely();
        fingerprintHelperFragment.cancel(FingerprintHelperFragment.USER_CANCELED_FROM_NONE);
    }
}
