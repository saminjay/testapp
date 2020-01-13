package com.example.saminjay.testapp;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.util.Log;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.AspectRatio;
import androidx.camera.core.CameraX;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureConfig;
import androidx.camera.core.Preview;
import androidx.camera.core.PreviewConfig;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.android.gms.tasks.OnFailureListener;
import com.google.android.material.snackbar.Snackbar;
import com.google.firebase.FirebaseException;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException;
import com.google.firebase.auth.PhoneAuthCredential;
import com.google.firebase.auth.PhoneAuthProvider;
import com.google.firebase.firestore.DocumentReference;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.storage.FirebaseStorage;
import com.google.firebase.storage.StorageReference;
import com.google.firebase.storage.UploadTask;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivity extends AppCompatActivity {

    private static final String[] REQUIRED_PERMISSIONS = new String[]{"android.permission.CAMERA", "android.permission.INTERNET", "android.permission.WRITE_EXTERNAL_STORAGE", "android.permission.READ_EXTERNAL_STORAGE"};
    private static final int REQUEST_CODE_PERMISSIONS = 10;

    private PhoneAuthProvider.OnVerificationStateChangedCallbacks callbacks;
    private FirebaseAuth firebaseAuth;
    private Timer T;
    private int count;
    private TextView timeoutTextView;
    private TextureView textureView;
    private ImageView imageView;
    private EditText phoneNumberEditText;
    private EditText otpCodeEditText;

    private boolean isImageCaptured = false;
    private boolean isImageUploaded = false;
    private boolean isVerificationCompleted = false;
    private boolean isOTPGenerated = false;
    private boolean isUserSuspicious;

    private String imageUrl;
    private String phoneNumber;
    private String mVerificationId;

    private FirebaseFirestore db;
    private StorageReference storageRef;
    private Uri imageFileUri;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        if (allPermissionsGranted()) {
            startCamera();
        } else {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS);
        }

        db = FirebaseFirestore.getInstance();
        storageRef = FirebaseStorage.getInstance().getReference();
        firebaseAuth = FirebaseAuth.getInstance();

        addCallbacksForOTPVerifications();

        imageView = findViewById(R.id.image);
        timeoutTextView = findViewById(R.id.timeout_text_view);
        phoneNumberEditText = findViewById(R.id.phone_number);
        otpCodeEditText = findViewById(R.id.otp_edit_text);
        textureView = findViewById(R.id.texture_view);

        Button generateOTPButton = findViewById(R.id.submit_phone_number);
        Button submitOTPButton = findViewById(R.id.submit_otp);

        T = new Timer();

        generateOTPButton.setOnClickListener(v -> {
            InputMethodManager imm = (InputMethodManager) MainActivity.this.getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null)
                imm.hideSoftInputFromWindow(v.getWindowToken(), 0);

            phoneNumber = phoneNumberEditText.getText().toString().trim();
            if (!isPhoneNumberValid()) {
                Snackbar.make(v, "Phone Number is not valid", Snackbar.LENGTH_SHORT).show();
            } else {
                if (isImageCaptured) {
                    getData();
                } else {
                    Snackbar.make(v, "Capture Image First.", Snackbar.LENGTH_SHORT).show();
                }
            }
        });

        submitOTPButton.setOnClickListener(v -> {
            InputMethodManager imm = (InputMethodManager) MainActivity.this.getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null)
                imm.hideSoftInputFromWindow(v.getWindowToken(), 0);

            if (isOTPGenerated) {
                String code = otpCodeEditText.getText().toString().trim();
                verifyOTP(mVerificationId, code);
            } else {
                Snackbar.make(v, "Generate OTP First", Snackbar.LENGTH_SHORT).show();
            }
        });
    }

    private void getData() {
        DocumentReference documentRef = db.collection("registered")
                .document(phoneNumber);
        documentRef.get()
                .addOnCompleteListener((task) -> {
                    if (task.isSuccessful()) {
                        DocumentSnapshot document = task.getResult();
                        assert document != null;
                        if (document.exists()) {
                            long visitCount = (long) document.get("visitCount") + 1;
                            Snackbar.make(textureView, "Welcome back for " + visitCount + " time.", Snackbar.LENGTH_LONG).show();
                            documentRef.update("visitCount", FieldValue.increment(1));
                            reset();
                        } else {
                            Log.e("adasd","getData");
                            generateOTP();
                            uploadImage();
                        }
                    } else {
                        Log.w("getData", "Error getting documents.", task.getException());
                    }
                });
    }

    private void uploadImage() {
        StorageReference imageRef = storageRef.child("images/" + imageFileUri.getLastPathSegment());
        UploadTask uploadTask = imageRef.putFile(imageFileUri);
        uploadTask.addOnSuccessListener(taskSnapshot -> {
            imageRef.getDownloadUrl().addOnSuccessListener(uri -> {
                imageUrl = uri.toString();
                isImageUploaded = true;
                if (isVerificationCompleted) {
                    if (isUserSuspicious) {
                        addSuspiciousUser();
                    } else {
                        addData();
                    }
                }
            });
        });
    }

    private void addData() {
        Map<String, Object> userdata = new HashMap<>();
        userdata.put("imageUrl", imageUrl);
        userdata.put("visitCount", 1);

        db.collection("registered")
                .document(phoneNumber)
                .set(userdata)
                .addOnSuccessListener(aVoid -> {

                    Snackbar.make(textureView, "New Visitor Saved!", Snackbar.LENGTH_LONG).show();
                })
                .addOnFailureListener(e -> {

                    Log.w("addData -> onFailure", "Error adding document", e);
                });
        reset();
    }

    private void addSuspiciousUser() {
        Map<String, Object> userdata = new HashMap<>();
        userdata.put("imageUrl", imageUrl);

        db.collection("suspicious")
                .document(phoneNumber)
                .set(userdata)
                .addOnSuccessListener(aVoid -> {

                    Snackbar.make(textureView, "Suspicious Visitor Saved!", Snackbar.LENGTH_LONG).show();
                })
                .addOnFailureListener(e -> {

                    Log.w("addData -> onFailure", "Error adding document", e);
                });
        reset();
    }

    private boolean isPhoneNumberValid() {
        Pattern pattern = Pattern.compile("[0-9]{10}");
        Matcher matcher = pattern.matcher(phoneNumber);
        return matcher.matches();
    }

    private void startCamera() {
        //make sure there isn't another camera instance running before starting
        CameraX.unbindAll();

        //config obj for preview/viewfinder thingy.
        PreviewConfig pConfig = new PreviewConfig.Builder()
                .setTargetAspectRatio(AspectRatio.RATIO_4_3)
                .setLensFacing(CameraX.LensFacing.FRONT)
                .build();
        Preview preview = new Preview(pConfig); //lets build it

        //to update the surface texture we have to destroy it first, then re-add it
        preview.setOnPreviewOutputUpdateListener(
                output -> {
                    ViewGroup parent = (ViewGroup) textureView.getParent();
                    parent.removeView(textureView);
                    parent.addView(textureView, 0);

                    textureView.setSurfaceTexture(output.getSurfaceTexture());
                });

        ImageCaptureConfig imgCapConfig = new ImageCaptureConfig.Builder()
                .setCaptureMode(ImageCapture.CaptureMode.MIN_LATENCY)
                .setLensFacing(CameraX.LensFacing.FRONT)
                .setTargetRotation(getWindowManager().getDefaultDisplay().getRotation())
                .build();

        final ImageCapture imgCap = new ImageCapture(imgCapConfig);
        ExecutorService executorService = Executors.newSingleThreadExecutor();
        findViewById(R.id.click_photo_button).setOnClickListener(v -> {
            File file = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM) + "/redCarpetUp" + System.currentTimeMillis() + ".jpg");
            imgCap.takePicture(file, executorService, new ImageCapture.OnImageSavedListener() {

                @Override
                public void onImageSaved(@NonNull File file) {
                    try {
                        imageFileUri = Uri.fromFile(file);
                        Bitmap bitmap = BitmapFactory.decodeFile(file.getAbsolutePath());
                        ExifInterface exif = new ExifInterface(file.getAbsoluteFile().toString());
                        int orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
                        Matrix matrix = new Matrix();
                        switch (orientation) {
                            case ExifInterface.ORIENTATION_ROTATE_90:
                                matrix.postRotate(90F);
                                break;
                            case ExifInterface.ORIENTATION_ROTATE_180:
                                matrix.postRotate(180F);
                                break;
                            case ExifInterface.ORIENTATION_ROTATE_270:
                                matrix.postRotate(270F);
                                break;
                        }
                        Bitmap rotatedBitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);
                        bitmap.recycle();
                        runOnUiThread(() -> {
                            imageView.setVisibility(View.VISIBLE);
                            imageView.setImageBitmap(rotatedBitmap);
                        });
                        isImageCaptured = true;
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }

                @Override
                public void onError(@NonNull ImageCapture.ImageCaptureError imageCaptureError, @NonNull String message, @Nullable Throwable cause) {
                    Log.e("ImageCapture", message + "\n", cause);
                    Snackbar.make(v, file.getAbsolutePath(), Snackbar.LENGTH_SHORT).show();
                }
            });
        });
        //bind to lifecycle:
        CameraX.bindToLifecycle(this, imgCap, preview);
    }

    private void generateOTP() {
        PhoneAuthProvider.getInstance().verifyPhoneNumber(
                "+91" + phoneNumber,        // Phone Number
                60,                         // Timeout Duration
                TimeUnit.SECONDS,              // Timeout Unit
                MainActivity.this,     // Activity for callback binding
                callbacks                      // OnVerificationStateChangedCallbacks
        );
        isOTPGenerated = true;
    }

    private void addCallbacksForOTPVerifications() {
        callbacks = new PhoneAuthProvider.OnVerificationStateChangedCallbacks() {
            @Override
            public void onVerificationCompleted(@NonNull PhoneAuthCredential phoneAuthCredential) {
                checkAuthCredential(phoneAuthCredential);
            }

            @Override
            public void onVerificationFailed(@NonNull FirebaseException e) {
                if (e instanceof FirebaseAuthInvalidCredentialsException) {
                    Snackbar.make(textureView, "Invalid Phone Number", Snackbar.LENGTH_SHORT).show();
                }
            }

            @Override
            public void onCodeSent(@NonNull String s, @NonNull PhoneAuthProvider.ForceResendingToken forceResendingToken) {
                mVerificationId = s;
                startTimeoutCountdown();
                Log.d("onCodeSent: ", s);
            }
        };
    }

    private void checkAuthCredential(PhoneAuthCredential phoneAuthCredential) {
        firebaseAuth.signInWithCredential(phoneAuthCredential)
                .addOnCompleteListener(task -> {
                    if (task.isSuccessful()) {
                        stopTimer();
                        isVerificationCompleted = true;
                        isUserSuspicious = false;
                        if (isImageUploaded) {
                            addData();
                        }
                    } else {
                        stopTimer();
                        isVerificationCompleted = true;
                        isUserSuspicious = true;
                        if (isImageUploaded) {
                            addSuspiciousUser();
                        }
                    }
                });
        firebaseAuth.signOut();
    }

    private void verifyOTP(String verificationId, String code) {
        PhoneAuthCredential phoneAuthCredential = PhoneAuthProvider.getCredential(verificationId, code);
        checkAuthCredential(phoneAuthCredential);
    }

    @SuppressLint("SetTextI18n")
    public void startTimeoutCountdown() {
        count = 60;
        timeoutTextView.setText(Integer.toString(count));
        T.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                runOnUiThread(() -> timeoutTextView.setText(Integer.toString(count)));
                count--;
            }
        }, 1000, 1000);
    }

    private void reset() {
        imageView.setVisibility(View.GONE);
        isOTPGenerated = false;
        isUserSuspicious = false;
        isVerificationCompleted = false;
        isImageUploaded = false;
        isImageCaptured = false;
        timeoutTextView.setText("");
        phoneNumberEditText.setText("");
        otpCodeEditText.setText("");
        File file = new File(imageFileUri.getPath());
        if(file.exists()) file.delete();
    }

    public void stopTimer() {
        T.cancel();
        T = new Timer();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        //start camera when permissions have been granted otherwise exit app
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted()) {
                startCamera();
            } else {
                Toast.makeText(this, "Permissions not granted by the user.", Toast.LENGTH_SHORT).show();
                finish();
            }
        }
    }

    private boolean allPermissionsGranted() {
        //check if req permissions have been granted
        for (String permission : REQUIRED_PERMISSIONS) {
            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }
}