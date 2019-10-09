# Android biometric for apps that use legacy support libraries
Android biometric library inspired from [androidx.biometric](https://developer.android.com/reference/androidx/biometric/package-summary) for non androidx apps.

## Example 
See example app
```java
Executor executor = null;
if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
    executor = MainActivity.this.getMainExecutor();
}
mBiometricPrompt = new BiometricPrompt(MainActivity.this, executor, new BiometricPrompt.AuthenticationCallback() {
    @Override
    public void onAuthenticationError(int errorCode, @NonNull CharSequence errString) {
        super.onAuthenticationError(errorCode, errString);
        if (errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON) {
            // Just negative button tap
            return;
        }
        new AlertDialog.Builder(MainActivity.this)
                .setTitle("Error")
                .setMessage(errorCode + ": " + errString)
                .create()
                .show();

    }

    @Override
    public void onAuthenticationSucceeded(@NonNull BiometricPrompt.AuthenticationResult result) {
        super.onAuthenticationSucceeded(result);
    }

    @Override
    public void onAuthenticationFailed() {
        super.onAuthenticationFailed();
    }
});

BiometricPrompt.PromptInfo promptInfo = new BiometricPrompt.PromptInfo.Builder()
        .setTitle("Set the title to display.")
        .setSubtitle("Set the subtitle to display.")
        .setDescription("Set the description to display")
        .setNegativeButtonText("Negative Button")
        .build();

mBiometricPrompt.authenticate(promptInfo);
```
