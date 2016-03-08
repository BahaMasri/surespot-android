package com.twofours.surespot.chat;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.Random;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import android.app.Activity;
import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapFactory.Options;
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.provider.MediaStore.Images;

// import ch.boye.httpclientandroidlib.androidextra.Base64
import java.io.UnsupportedEncodingException;

import com.twofours.surespot.SurespotApplication;
import com.twofours.surespot.common.FileUtils;
import com.twofours.surespot.common.SurespotConfiguration;
import com.twofours.surespot.common.SurespotConstants;
import com.twofours.surespot.common.SurespotLog;
import com.twofours.surespot.common.Utils;
import com.twofours.surespot.encryption.EncryptionController;
import com.twofours.surespot.identity.IdentityController;
import com.twofours.surespot.images.MessageImageDownloader;
import com.twofours.surespot.network.IAsyncCallback;
import com.twofours.surespot.network.IAsyncCallbackTriplet;
import com.twofours.surespot.network.NetworkController;

public class ChatUtils {
    private static final String TAG = "ChatUtils";
    private static Random mImageUploadFileRandom = new Random();

    public static String getOtherUser(String from, String to) {
        return to.equals(IdentityController.getLoggedInUser()) ? from : to;
    }

    public static String getSpot(String from, String to) {
        return (to.compareTo(from) < 0 ? to + ":" + from : from + ":" + to);
    }

    public static String getSpot(SurespotMessage message) {
        return getSpot(message.getTo(), message.getFrom());
    }

    public static String getOtherSpotUser(String spot, String user) {
        String[] split = spot.split(":");

        return split[0].equals(user) ? split[1] : split[0];
    }

    public static boolean isMyMessage(SurespotMessage message) {
        return message.getFrom().equals(IdentityController.getLoggedInUser());
    }

    public static SurespotMessage buildPlainMessage(String to, String mimeType, CharSequence plainData, String iv) {
        SurespotMessage chatMessage = new SurespotMessage();
        chatMessage.setFrom(IdentityController.getLoggedInUser());
        chatMessage.setTo(to);
        chatMessage.setPlainData(plainData);
        chatMessage.setIv(iv);

        // store the mime type outside teh encrypted envelope, this way we can offload resources
        // by mime type
        chatMessage.setMimeType(mimeType);
        return chatMessage;
    }

    public static SurespotMessage buildPlainBinaryMessage(String to, String mimeType, byte[] plainData, String iv) {
        SurespotMessage chatMessage = new SurespotMessage();
        chatMessage.setFrom(IdentityController.getLoggedInUser());
        chatMessage.setTo(to);
        chatMessage.setPlainBinaryData(plainData);
        chatMessage.setIv(iv);

        // store the mime type outside teh encrypted envelope, this way we can offload resources
        // by mime type
        chatMessage.setMimeType(mimeType);
        return chatMessage;
    }

    public static SurespotMessage buildMessage(String to, String mimeType, String plainData, String iv, String cipherData) {
        SurespotMessage chatMessage = new SurespotMessage();
        chatMessage.setFrom(IdentityController.getLoggedInUser());
        chatMessage.setFromVersion(IdentityController.getOurLatestVersion());
        chatMessage.setTo(to);
        chatMessage.setToVersion(IdentityController.getTheirLatestVersion(to));
        chatMessage.setData(cipherData);
        chatMessage.setPlainData(plainData);
        chatMessage.setIv(iv);

        // store the mime type outside teh encrypted envelope, this way we can offload resources
        // by mime type
        chatMessage.setMimeType(mimeType);
        return chatMessage;
    }

    public static void uploadPictureMessageAsync(final Activity activity, final ChatController chatController, final NetworkController networkController,
                                                 final Uri imageUri, final String to, final boolean scale, final IAsyncCallback<Boolean> callback) {

        Runnable runnable = new Runnable() {

            @Override
            public void run() {
                SurespotLog.v(TAG, "uploadPictureMessageAsync");
                try {
                    Bitmap bitmap = null;
                    InputStream dataStream = null;
                    if (scale) {
                        SurespotLog.v(TAG, "scalingImage");
                        bitmap = decodeSampledBitmapFromUri(activity, imageUri, -1, SurespotConstants.MESSAGE_IMAGE_DIMENSION);

                        if (bitmap != null) {
                            final Bitmap finalBitmap = bitmap;
                            final PipedOutputStream pos = new PipedOutputStream();
                            dataStream = new PipedInputStream(pos);
                            Runnable runnable = new Runnable() {
                                @Override
                                public void run() {
                                    SurespotLog.v(TAG, "compressingImage");
                                    finalBitmap.compress(Bitmap.CompressFormat.JPEG, 75, pos);
                                    try {
                                        pos.close();
                                        SurespotLog.v(TAG, "imageCompressed");
                                    } catch (IOException e) {
                                        SurespotLog.w(TAG, e, "error compressing image");
                                    }
                                }
                            };
                            SurespotApplication.THREAD_POOL_EXECUTOR.execute(runnable);
                        }
                    } else {
                        dataStream = activity.getContentResolver().openInputStream(imageUri);
                    }

                    if (dataStream != null) {

                        PipedOutputStream encryptionOutputStream = new PipedOutputStream();
                        final PipedInputStream encryptionInputStream = new PipedInputStream(encryptionOutputStream);

                        final String ourVersion = IdentityController.getOurLatestVersion();
                        final String theirVersion = IdentityController.getTheirLatestVersion(to);

                        final String iv = EncryptionController.runEncryptTask(ourVersion, to, theirVersion, new BufferedInputStream(dataStream),
                                encryptionOutputStream);

                        if (scale) {
                            // use iv as key

                            if (bitmap != null) {
                                // scale to display size
                                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                                bitmap.compress(Bitmap.CompressFormat.JPEG, 75, bos);
                                bitmap = getSampledImage(bos.toByteArray());
                                bos.close();

                            }
                        } else {
                            // scale to display size
                            bitmap = getSampledImage(Utils.inputStreamToBytes(activity.getContentResolver().openInputStream(imageUri)));
                        }

                        // save encrypted image locally until we receive server confirmation
                        String localImageDir = FileUtils.getImageUploadDir(activity);
                        new File(localImageDir).mkdirs();

                        String localImageFilename = localImageDir + File.separator
                                + URLEncoder.encode(String.valueOf(mImageUploadFileRandom.nextInt()) + ".tmp", "UTF-8");
                        final File localImageFile = new File(localImageFilename);

                        localImageFile.createNewFile();
                        String localImageUri = Uri.fromFile(localImageFile).toString();
                        SurespotLog.v(TAG, "saving copy of encrypted image to: %s", localImageFilename);
                        SurespotMessage message = null;
                        if (bitmap != null) {
                            SurespotLog.v(TAG, "adding bitmap to cache: %s", localImageUri);

                            MessageImageDownloader.addBitmapToCache(localImageUri, bitmap);
                            message = buildMessage(to, SurespotConstants.MimeTypes.IMAGE, null, iv, localImageUri);
                            message.setId(null);

                            final SurespotMessage finalMessage = message;
                            activity.runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    SurespotLog.v(TAG, "adding local image message %s", finalMessage);
                                    chatController.addMessage(activity, finalMessage);
                                }
                            });
                        }

                        final SurespotMessage finalMessage = message;
                        Runnable saveFileRunnable = new Runnable() {
                            @Override
                            public void run() {

                                // save encrypted image to disk
                                FileOutputStream fileSaveStream;
                                try {
                                    fileSaveStream = new FileOutputStream(localImageFile);

                                    int bufferSize = 1024;
                                    byte[] buffer = new byte[bufferSize];

                                    int len = 0;
                                    while ((len = encryptionInputStream.read(buffer)) != -1) {
                                        fileSaveStream.write(buffer, 0, len);
                                    }
                                    fileSaveStream.close();
                                    encryptionInputStream.close();

                                } catch (IOException e) {
                                    SurespotLog.w(TAG, e, "uploadPictureMessageAsync");
                                    if (finalMessage != null) {
                                        finalMessage.setErrorStatus(500);
                                    }
                                    callback.handleResponse(true);
                                    return;
                                }

                                // upload encrypted image to server
                                FileInputStream uploadStream;
                                try {
                                    uploadStream = new FileInputStream(localImageFile);
                                } catch (FileNotFoundException e) {
                                    SurespotLog.w(TAG, e, "uploadPictureMessageAsync");
                                    if (finalMessage != null) {
                                        finalMessage.setErrorStatus(500);
                                    }
                                    callback.handleResponse(true);
                                    return;
                                }

                                networkController.postFileStream(activity, ourVersion, to, theirVersion, iv, uploadStream, SurespotConstants.MimeTypes.IMAGE,
                                        new IAsyncCallback<Integer>() {

                                            @Override
                                            public void handleResponse(Integer statusCode) {
                                                // if it failed update the message
                                                SurespotLog.v(TAG, "postFileStream complete, result: %d", statusCode);
                                                ChatAdapter chatAdapter = null;
                                                switch (statusCode) {
                                                    case 200:
                                                        break;
                                                    case 402:
                                                        if (finalMessage != null) {
                                                            finalMessage.setErrorStatus(402);
                                                        }
                                                        chatAdapter = chatController.getChatAdapter(activity, to);
                                                        if (chatAdapter != null) {
                                                            chatAdapter.notifyDataSetChanged();
                                                        }
                                                        break;
                                                    default:
                                                        if (finalMessage != null) {
                                                            finalMessage.setErrorStatus(500);
                                                        }
                                                        chatAdapter = chatController.getChatAdapter(activity, to);
                                                        if (chatAdapter != null) {
                                                            chatAdapter.notifyDataSetChanged();
                                                        }
                                                }

                                                callback.handleResponse(true);
                                            }
                                        });

                            }
                        };

                        SurespotApplication.THREAD_POOL_EXECUTOR.execute(saveFileRunnable);

                    } else {
                        callback.handleResponse(false);
                    }
                } catch (IOException e) {
                    SurespotLog.w(TAG, e, "uploadPictureMessageAsync");
                }
            }
        };

        SurespotApplication.THREAD_POOL_EXECUTOR.execute(runnable);

    }

    public static void uploadFriendImageAsync(final Activity activity, final NetworkController networkController, final Uri imageUri, final String friendName,
                                              final IAsyncCallbackTriplet<String, String, String> callback) {

        Runnable runnable = new Runnable() {

            @Override
            public void run() {
                SurespotLog.v(TAG, "uploadFriendImageAsync");
                try {
                    InputStream dataStream = activity.getContentResolver().openInputStream(imageUri);
                    PipedOutputStream encryptionOutputStream = new PipedOutputStream();
                    final PipedInputStream encryptionInputStream = new PipedInputStream(encryptionOutputStream);

                    final String ourVersion = IdentityController.getOurLatestVersion();
                    final String username = IdentityController.getLoggedInUser();
                    final String iv = EncryptionController.runEncryptTask(ourVersion, username, ourVersion, new BufferedInputStream(dataStream),
                            encryptionOutputStream);

                    networkController.postFriendImageStream(activity, friendName, ourVersion, iv, encryptionInputStream, new IAsyncCallback<String>() {

                        @Override
                        public void handleResponse(String uri) {
                            if (uri != null) {
                                callback.handleResponse(uri, ourVersion, iv);
                            } else {
                                callback.handleResponse(null, null, null);
                            }
                        }
                    });
                } catch (IOException e) {
                    callback.handleResponse(null, null, null);
                    SurespotLog.w(TAG, e, "uploadFriendImageAsync");
                }
            }
        };

        SurespotApplication.THREAD_POOL_EXECUTOR.execute(runnable);

    }

    public static void uploadVoiceMessageAsync(final Activity activity, final ChatController chatController, final NetworkController networkController,
                                               final Uri audioUri, final String to, final IAsyncCallback<Boolean> callback) {

        Runnable runnable = new Runnable() {

            @Override
            public void run() {
                SurespotLog.v(TAG, "uploadVoiceMessageAsync");
                try {
                    InputStream dataStream = null;

                    dataStream = activity.getContentResolver().openInputStream(audioUri);

                    if (dataStream != null) {

                        PipedOutputStream encryptionOutputStream = new PipedOutputStream();
                        final PipedInputStream encryptionInputStream = new PipedInputStream(encryptionOutputStream);

                        final String ourVersion = IdentityController.getOurLatestVersion();
                        final String theirVersion = IdentityController.getTheirLatestVersion(to);

                        final String iv = EncryptionController.runEncryptTask(ourVersion, to, theirVersion, new BufferedInputStream(dataStream),
                                encryptionOutputStream);

                        // save encrypted audio locally until we receive server confirmation
                        String localImageDir = FileUtils.getImageUploadDir(activity);
                        new File(localImageDir).mkdirs();

                        String localImageFilename = localImageDir + File.separator
                                + URLEncoder.encode(String.valueOf(mImageUploadFileRandom.nextInt()) + ".tmp", "UTF-8");
                        final File localImageFile = new File(localImageFilename);

                        localImageFile.createNewFile();
                        final String localImageUri = Uri.fromFile(localImageFile).toString();
                        SurespotLog.v(TAG, "saving copy of encrypted image to: %s", localImageFilename);

                        Runnable saveFileRunnable = new Runnable() {
                            @Override
                            public void run() {

                                SurespotMessage message = null;
                                // save encrypted voice message to disk
                                FileOutputStream fileSaveStream;
                                try {
                                    fileSaveStream = new FileOutputStream(localImageFile);

                                    int bufferSize = 1024;
                                    byte[] buffer = new byte[bufferSize];

                                    int len = 0;
                                    while ((len = encryptionInputStream.read(buffer)) != -1) {
                                        fileSaveStream.write(buffer, 0, len);
                                    }
                                    fileSaveStream.close();
                                    encryptionInputStream.close();

                                    message = buildMessage(to, SurespotConstants.MimeTypes.M4A, null, iv, localImageUri);
                                    message.setId(null);

                                    final SurespotMessage finalMessage = message;
                                    activity.runOnUiThread(new Runnable() {
                                        @Override
                                        public void run() {
                                            SurespotLog.v(TAG, "adding local voice message %s", finalMessage);
                                            chatController.addMessage(activity, finalMessage);
                                        }
                                    });

                                } catch (IOException e) {
                                    SurespotLog.w(TAG, e, "uploadVoiceMessageAsync");
                                    if (message != null) {
                                        message.setErrorStatus(500);
                                    }
                                    callback.handleResponse(true);
                                    return;
                                }

                                // upload encrypted image to server
                                FileInputStream uploadStream;
                                try {
                                    uploadStream = new FileInputStream(localImageFile);
                                } catch (FileNotFoundException e) {
                                    SurespotLog.w(TAG, e, "uploadVoiceMessageAsync");
                                    if (message != null) {
                                        message.setErrorStatus(500);
                                    }
                                    callback.handleResponse(true);
                                    return;
                                }

                                final SurespotMessage finalMessage = message;
                                networkController.postFileStream(activity, ourVersion, to, theirVersion, iv, uploadStream, SurespotConstants.MimeTypes.M4A,
                                        new IAsyncCallback<Integer>() {

                                            @Override
                                            public void handleResponse(Integer statusCode) {
                                                // if it failed update the message
                                                SurespotLog.v(TAG, "postFileStream complete, result: %d", statusCode);
                                                ChatAdapter chatAdapter = null;
                                                switch (statusCode) {
                                                    case 200:
                                                        break;
                                                    case 402:
                                                        if (finalMessage != null) {
                                                            finalMessage.setErrorStatus(402);
                                                        }
                                                        chatAdapter = chatController.getChatAdapter(activity, to);
                                                        if (chatAdapter != null) {
                                                            chatAdapter.notifyDataSetChanged();
                                                        }
                                                        break;
                                                    default:
                                                        if (finalMessage != null) {
                                                            finalMessage.setErrorStatus(500);
                                                        }
                                                        chatAdapter = chatController.getChatAdapter(activity, to);
                                                        if (chatAdapter != null) {
                                                            chatAdapter.notifyDataSetChanged();
                                                        }
                                                }

                                                callback.handleResponse(true);
                                            }
                                        });

                            }
                        };

                        SurespotApplication.THREAD_POOL_EXECUTOR.execute(saveFileRunnable);

                    } else {
                        callback.handleResponse(false);
                    }
                } catch (IOException e) {
                    SurespotLog.w(TAG, e, "uploadPictureMessageAsync");
                    callback.handleResponse(false);
                }
            }
        };

        SurespotApplication.THREAD_POOL_EXECUTOR.execute(runnable);

    }

    public static void resendFileMessage(Context context, NetworkController networkController, final SurespotMessage message,
                                         final IAsyncCallback<Integer> callback) {

        // upload encrypted file to server
        FileInputStream uploadStream = null;
        try {
            if (message.getData().startsWith("file")) {
                uploadStream = new FileInputStream(new File(new URI(message.getData())));
            } else {
                callback.handleResponse(500);
            }
        } catch (IllegalArgumentException e) {
            SurespotLog.w(TAG, e, "uploadPictureMessageAsync");
            callback.handleResponse(500);
            return;
        } catch (FileNotFoundException e) {
            SurespotLog.w(TAG, e, "uploadPictureMessageAsync");
            callback.handleResponse(500);
            return;
        } catch (URISyntaxException e) {
            SurespotLog.w(TAG, e, "uploadPictureMessageAsync");
            callback.handleResponse(500);
            return;
        }

        networkController.postFileStream(context, message.getOurVersion(), message.getTo(), message.getTheirVersion(), message.getIv(), uploadStream,
                message.getMimeType(), new IAsyncCallback<Integer>() {

                    @Override
                    public void handleResponse(Integer statusCode) {
                        SurespotLog.v(TAG, "postFileStream complete, result: %d", statusCode);
                        callback.handleResponse(statusCode);
                    }
                });
    }

    public static Bitmap decodeSampledBitmapFromUri(Context context, Uri imageUri, int rotate, int maxDimension) {
        //

        try {// First decode with inJustDecodeBounds=true to check dimensions
            BitmapFactory.Options options = new BitmapFactory.Options();
            InputStream is;
            options.inJustDecodeBounds = true;

            is = context.getContentResolver().openInputStream(imageUri);
            Bitmap bm = BitmapFactory.decodeStream(is, null, options);
            is.close();

            // rotate as necessary
            int rotatedWidth, rotatedHeight;

            int orientation = 0;

            // if we have a rotation use it otherwise look at the EXIF
            if (rotate > -1) {
                orientation = rotate;
            } else {
                orientation = (int) rotationForImage(context, imageUri);
            }
            if (orientation == 90 || orientation == 270) {
                rotatedWidth = options.outHeight;
                rotatedHeight = options.outWidth;
            } else {
                rotatedWidth = options.outWidth;
                rotatedHeight = options.outHeight;
            }

            Bitmap srcBitmap;
            is = context.getContentResolver().openInputStream(imageUri);
            if (rotatedWidth > maxDimension || rotatedHeight > maxDimension) {
                float widthRatio = ((float) rotatedWidth) / ((float) maxDimension);
                float heightRatio = ((float) rotatedHeight) / ((float) maxDimension);
                float maxRatio = Math.max(widthRatio, heightRatio);

                // Create the bitmap from file
                options = new BitmapFactory.Options();
                options.inSampleSize = (int) Math.round(maxRatio);
                SurespotLog.v(TAG, "Rotated width: " + rotatedWidth + ", height: " + rotatedHeight + ", insamplesize: " + options.inSampleSize);
                srcBitmap = BitmapFactory.decodeStream(is, null, options);
            } else {
                srcBitmap = BitmapFactory.decodeStream(is);
            }

            is.close();
            if (srcBitmap != null) {

                SurespotLog.v(TAG, "loaded width: " + srcBitmap.getWidth() + ", height: " + srcBitmap.getHeight());

                if (orientation > 0) {
                    Matrix matrix = new Matrix();
                    matrix.postRotate(orientation);

                    srcBitmap = Bitmap.createBitmap(srcBitmap, 0, 0, srcBitmap.getWidth(), srcBitmap.getHeight(), matrix, true);
                    SurespotLog.v(TAG, "post rotated width: " + srcBitmap.getWidth() + ", height: " + srcBitmap.getHeight());
                }
            }

            return srcBitmap;
        } catch (Exception e) {
            SurespotLog.w(TAG, e, "decodeSampledBitmapFromUri");
        }
        return null;

    }

    public static Bitmap getSampledImage(byte[] data) {
        BitmapFactory.Options options = new Options();
        decodeBounds(options, data);

        int reqHeight = SurespotConfiguration.getImageDisplayHeight();
        if (options.outHeight > reqHeight) {
            options.inSampleSize = calculateInSampleSize(options, 0, reqHeight);
            SurespotLog.v(TAG, "getSampledImage, inSampleSize: " + options.inSampleSize);
        }

        options.inJustDecodeBounds = false;
        return BitmapFactory.decodeByteArray(data, 0, data.length, options);
    }

    private static void decodeBounds(Options options, byte[] data) {
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(data, 0, data.length, options);
    }

    private static int calculateInSampleSize(BitmapFactory.Options options, int reqWidth, int reqHeight) {
        // Raw height and width of image
        final int height = options.outHeight;
        final int width = options.outWidth;
        int inSampleSize = 1;

        if (height > reqHeight || width > reqWidth) {
            // if (width > height) {
            inSampleSize = Math.round((float) height / (float) reqHeight);
            // }
            // else {
            // inSampleSize = Math.round((float) width / (float) reqWidth);
            // }

            // This offers some additional logic in case the image has a strange
            // aspect ratio. For example, a panorama may have a much larger
            // width than height. In these cases the total pixels might still
            // end up being too large to fit comfortably in memory, so we should
            // be more aggressive with sample down the image (=larger
            // inSampleSize).

            if (reqWidth > 0 && reqHeight > 0) {
                final float totalPixels = width * height;

                // Anything more than 2x the requested pixels we'll sample down
                // further.
                final float totalReqPixelsCap = reqWidth * reqHeight * 2;

                while (totalPixels / (inSampleSize * inSampleSize) > totalReqPixelsCap) {
                    inSampleSize++;

                }
            }
        }
        return inSampleSize;
    }

    public static float rotationForImage(Context context, Uri uri) {

        if (uri.getScheme().equals("content")) {

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                try {
                    String path = getRealPathFromURI_API19(context, uri);

                    float rotation2 = getRotationFromPath(path);

                    if (rotation2 == 0) {
                        // this one appears to work all the time for local images!
                        rotation2 = getRotationFromPath(Environment.getExternalStorageDirectory().getAbsolutePath() + "/" + path);
                    }

                    if (rotation2 == 0) {
                        rotation2 = getRotationFromPath("file://" + path);
                    }

                    if (rotation2 != 0) {
                        return rotation2;
                    }
                } catch (Exception e) {
                    //fallback to old code
                }
            }


            String[] projection = {Images.Media.ORIENTATION}; //{Images.ImageColumns.ORIENTATION};
            Cursor c = context.getContentResolver().query(uri, projection, null, null, null);
            if (c.moveToFirst()) {
                SurespotLog.d(TAG, "Image orientation: %d", c.getInt(0));
                return c.getInt(0);
            }

        } else if (uri.getScheme().equals("file")) {
            return getRotationFromPath(uri.getPath());
        }
        return 0f;
    }

    private static float getRotationFromPath(String path) {
        try {
            ExifInterface exif = new ExifInterface(path);
            int rotation = (int) exifOrientationToDegrees(exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL));
            return rotation;
        } catch (IOException e) {
            SurespotLog.e(TAG, e, "Error checking exif");
        }
        return 0;
    }

    @android.annotation.SuppressLint("NewApi")
    public static String getRealPathFromURI_API11to18(Context context, Uri contentUri) {
        String[] proj = {MediaStore.Images.Media.DATA};
        String result = null;

        android.content.CursorLoader cursorLoader = new android.content.CursorLoader(
                context,
                contentUri, proj, null, null, null);
        Cursor cursor = cursorLoader.loadInBackground();

        if (cursor != null) {
            int column_index =
                    cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATA);
            cursor.moveToFirst();
            result = cursor.getString(column_index);
        }
        return result;
    }

    public static String getRealPathFromURI_BelowAPI11(Context context, Uri contentUri) {
        String[] proj = {MediaStore.Images.Media.DATA};
        Cursor cursor = context.getContentResolver().query(contentUri, proj, null, null, null);
        int column_index
                = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATA);
        cursor.moveToFirst();
        return cursor.getString(column_index);
    }

    @android.annotation.SuppressLint("NewApi")
    public static String getRealPathFromURI_API19(Context context, Uri uri) {
        String filePath = "";
        String wholeID = android.provider.DocumentsContract.getDocumentId(uri);

        // Split at colon, use second item in the array
        String id = wholeID.split(":")[1];

        String[] column = {MediaStore.Images.Media.DATA};

        // where id is equal to
        String sel = MediaStore.Images.Media._ID + "=?";

        Cursor cursor = context.getContentResolver().query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                column, sel, new String[]{id}, null);

        int columnIndex = cursor.getColumnIndex(column[0]);

        if (cursor.moveToFirst()) {
            filePath = cursor.getString(columnIndex);
        } else {
            if (wholeID.startsWith("primary:")) {
                filePath = wholeID.replace("primary:", "");
            } else {
                return wholeID;
            }
        }
        cursor.close();
        return filePath;
    }

    private static float exifOrientationToDegrees(int exifOrientation) {
        if (exifOrientation == ExifInterface.ORIENTATION_ROTATE_90) {
            return 90;
        } else if (exifOrientation == ExifInterface.ORIENTATION_ROTATE_180) {
            return 180;
        } else if (exifOrientation == ExifInterface.ORIENTATION_ROTATE_270) {
            return 270;
        }
        return 0;
    }

    public static JSONArray chatMessagesToJson(Collection<SurespotMessage> messages) {
        // avoid concurrent modification issues
        synchronized (messages) {
            SurespotMessage[] messageArray = messages.toArray(new SurespotMessage[messages.size()]);
            JSONArray jsonMessages = new JSONArray();

            for (SurespotMessage message : messageArray) {
                jsonMessages.put(message.toJSONObject());
            }

            return jsonMessages;
        }
    }

    public static ArrayList<SurespotMessage> jsonStringToChatMessages(String jsonMessageString) {

        ArrayList<SurespotMessage> messages = new ArrayList<SurespotMessage>();
        try {
            JSONArray jsonUM = new JSONArray(jsonMessageString);
            for (int i = 0; i < jsonUM.length(); i++) {
                messages.add(SurespotMessage.toSurespotMessage(jsonUM.getJSONObject(i)));
            }
        } catch (JSONException e) {
            SurespotLog.w(TAG, "jsonStringToChatMessages", e);
        }
        return messages;

    }

    public static ArrayList<SurespotMessage> jsonStringsToMessages(String jsonMessageString) {

        ArrayList<SurespotMessage> messages = new ArrayList<SurespotMessage>();
        try {
            JSONArray jsonUM = new JSONArray(jsonMessageString);
            for (int i = 0; i < jsonUM.length(); i++) {
                messages.add(SurespotMessage.toSurespotMessage(new JSONObject(jsonUM.getString(i))));
            }
        } catch (JSONException e) {
            SurespotLog.w(TAG, "jsonStringsToMessages", e);
        }
        return messages;

    }

    public static byte[] base64EncodeNowrap(byte[] buf) {
        return Base64.encode(buf, Base64.NO_WRAP);
    }

    public static byte[] base64DecodeNowrap(String buf) {
        return Base64.decode(buf, Base64.NO_WRAP);
    }

    public static byte[] base64Encode(byte[] buf) {
        return Base64.encode(buf, Base64.DEFAULT);
    }

    public static byte[] base64Decode(String buf) {
        return Base64.decode(buf, Base64.DEFAULT);
    }

    /**
     * Converts the string to the unicode format '\u0020'.
     * <p/>
     * This format is the Java source code format.
     * <p/>
     * <pre>
     *   CharUtils.unicodeEscaped(' ') = "\u0020"
     *   CharUtils.unicodeEscaped('A') = "\u0041"
     * </pre>
     *
     * @param ch the character to convert
     * @return the escaped unicode string
     */
    public static String unicodeEscaped(int ch) {
        if (ch < 0x10) {
            return "\\u000" + Integer.toHexString(ch);
        } else if (ch < 0x100) {
            return "\\u00" + Integer.toHexString(ch);
        } else if (ch < 0x1000) {
            return "\\u0" + Integer.toHexString(ch);
        }
        return "\\u" + Integer.toHexString(ch);
    }

    /**
     * Converts the string to the unicode format '\u0020'.
     * <p/>
     * This format is the Java source code format.
     * <p/>
     * If <code>null</code> is passed in, <code>null</code> will be returned.
     * <p/>
     * <pre>
     *   CharUtils.unicodeEscaped(null) = null
     *   CharUtils.unicodeEscaped(' ')  = "\u0020"
     *   CharUtils.unicodeEscaped('A')  = "\u0041"
     * </pre>
     *
     * @param ch the character to convert, may be null
     * @return the escaped unicode string, null if null input
     */
    public static String unicodeEscaped(Character ch) {
        if (ch == null) {
            return null;
        }
        return unicodeEscaped(ch.charValue());
    }

    public static class CodePoint {
        public int codePoint;
        public int start;
        public int end;
    }

    // iterate through codepoints http://bugs.sun.com/bugdatabase/view_bug.do?bug_id=5003547
    public static Iterable<CodePoint> codePoints(final String s) {
        return new Iterable<CodePoint>() {
            public Iterator<CodePoint> iterator() {
                return new Iterator<CodePoint>() {
                    int nextIndex = 0;

                    public boolean hasNext() {
                        return nextIndex < s.length();
                    }

                    public CodePoint next() {
                        int result = s.codePointAt(nextIndex);

                        CodePoint cp = new CodePoint();
                        cp.codePoint = result;
                        cp.start = nextIndex;
                        nextIndex += Character.charCount(result);
                        cp.end = nextIndex;
                        return cp;
                    }

                    public void remove() {
                        throw new UnsupportedOperationException();
                    }
                };
            }
        };
    }






/**
 * Utilities for encoding and decoding the Base64 representation of
 * binary data.  See RFCs <a
 * href="http://www.ietf.org/rfc/rfc2045.txt">2045</a> and <a
 * href="http://www.ietf.org/rfc/rfc3548.txt">3548</a>.
 */
public class Base64 {
    /**
     * Default values for encoder/decoder flags.
     */
    public static final int DEFAULT = 0;

    /**
     * Encoder flag bit to omit the padding '=' characters at the end
     * of the output (if any).
     */
    public static final int NO_PADDING = 1;

    /**
     * Encoder flag bit to omit all line terminators (i.e., the output
     * will be on one long line).
     */
    public static final int NO_WRAP = 2;

    /**
     * Encoder flag bit to indicate lines should be terminated with a
     * CRLF pair instead of just an LF.  Has no effect if {@code
     * NO_WRAP} is specified as well.
     */
    public static final int CRLF = 4;

    /**
     * Encoder/decoder flag bit to indicate using the "URL and
     * filename safe" variant of Base64 (see RFC 3548 section 4) where
     * {@code -} and {@code _} are used in place of {@code +} and
     * {@code /}.
     */
    public static final int URL_SAFE = 8;

    /**
     * Flag to pass to {@link Base64OutputStream} to indicate that it
     * should not close the output stream it is wrapping when it
     * itself is closed.
     */
    public static final int NO_CLOSE = 16;

    //  --------------------------------------------------------
    //  shared code
    //  --------------------------------------------------------

    /* package */ static abstract class Coder {
        public byte[] output;
        public int op;

        /**
         * Encode/decode another block of input data.  this.output is
         * provided by the caller, and must be big enough to hold all
         * the coded data.  On exit, this.opwill be set to the length
         * of the coded data.
         *
         * @param finish true if this is the final call to process for
         *        this object.  Will finalize the coder state and
         *        include any final bytes in the output.
         *
         * @return true if the input so far is good; false if some
         *         error has been detected in the input stream..
         */
        public abstract boolean process(byte[] input, int offset, int len, boolean finish);

        /**
         * @return the maximum number of bytes a call to process()
         * could produce for the given number of input bytes.  This may
         * be an overestimate.
         */
        public abstract int maxOutputSize(int len);
    }

    //  --------------------------------------------------------
    //  decoding
    //  --------------------------------------------------------

    /**
     * Decode the Base64-encoded data in input and return the data in
     * a new byte array.
     *
     * <p>The padding '=' characters at the end are considered optional, but
     * if any are present, there must be the correct number of them.
     *
     * @param str    the input String to decode, which is converted to
     *               bytes using the default charset
     * @param flags  controls certain features of the decoded output.
     *               Pass {@code DEFAULT} to decode standard Base64.
     *
     * @throws IllegalArgumentException if the input contains
     * incorrect padding
     */
    public static byte[] decode(String str, int flags) {
        return decode(str.getBytes(), flags);
    }

    /**
     * Decode the Base64-encoded data in input and return the data in
     * a new byte array.
     *
     * <p>The padding '=' characters at the end are considered optional, but
     * if any are present, there must be the correct number of them.
     *
     * @param input the input array to decode
     * @param flags  controls certain features of the decoded output.
     *               Pass {@code DEFAULT} to decode standard Base64.
     *
     * @throws IllegalArgumentException if the input contains
     * incorrect padding
     */
    public static byte[] decode(byte[] input, int flags) {
        return decode(input, 0, input.length, flags);
    }

    /**
     * Decode the Base64-encoded data in input and return the data in
     * a new byte array.
     *
     * <p>The padding '=' characters at the end are considered optional, but
     * if any are present, there must be the correct number of them.
     *
     * @param input  the data to decode
     * @param offset the position within the input array at which to start
     * @param len    the number of bytes of input to decode
     * @param flags  controls certain features of the decoded output.
     *               Pass {@code DEFAULT} to decode standard Base64.
     *
     * @throws IllegalArgumentException if the input contains
     * incorrect padding
     */
    public static byte[] decode(byte[] input, int offset, int len, int flags) {
        // Allocate space for the most data the input could represent.
        // (It could contain less if it contains whitespace, etc.)
        Decoder decoder = new Decoder(flags, new byte[len*3/4]);

        if (!decoder.process(input, offset, len, true)) {
            throw new IllegalArgumentException("bad base-64");
        }

        // Maybe we got lucky and allocated exactly enough output space.
        if (decoder.op == decoder.output.length) {
            return decoder.output;
        }

        // Need to shorten the array, so allocate a new one of the
        // right size and copy.
        byte[] temp = new byte[decoder.op];
        System.arraycopy(decoder.output, 0, temp, 0, decoder.op);
        return temp;
    }

    /* package */ static class Decoder extends Coder {
        /**
         * Lookup table for turning bytes into their position in the
         * Base64 alphabet.
         */
        private static final int DECODE[] = {
            -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
            -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
            -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, 62, -1, -1, -1, 63,
            52, 53, 54, 55, 56, 57, 58, 59, 60, 61, -1, -1, -1, -2, -1, -1,
            -1,  0,  1,  2,  3,  4,  5,  6,  7,  8,  9, 10, 11, 12, 13, 14,
            15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, -1, -1, -1, -1, -1,
            -1, 26, 27, 28, 29, 30, 31, 32, 33, 34, 35, 36, 37, 38, 39, 40,
            41, 42, 43, 44, 45, 46, 47, 48, 49, 50, 51, -1, -1, -1, -1, -1,
            -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
            -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
            -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
            -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
            -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
            -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
            -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
            -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
        };

        /**
         * Decode lookup table for the "web safe" variant (RFC 3548
         * sec. 4) where - and _ replace + and /.
         */
        private static final int DECODE_WEBSAFE[] = {
            -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
            -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
            -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, 62, -1, -1,
            52, 53, 54, 55, 56, 57, 58, 59, 60, 61, -1, -1, -1, -2, -1, -1,
            -1,  0,  1,  2,  3,  4,  5,  6,  7,  8,  9, 10, 11, 12, 13, 14,
            15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, -1, -1, -1, -1, 63,
            -1, 26, 27, 28, 29, 30, 31, 32, 33, 34, 35, 36, 37, 38, 39, 40,
            41, 42, 43, 44, 45, 46, 47, 48, 49, 50, 51, -1, -1, -1, -1, -1,
            -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
            -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
            -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
            -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
            -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
            -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
            -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
            -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
        };

        /** Non-data values in the DECODE arrays. */
        private static final int SKIP = -1;
        private static final int EQUALS = -2;

        /**
         * States 0-3 are reading through the next input tuple.
         * State 4 is having read one '=' and expecting exactly
         * one more.
         * State 5 is expecting no more data or padding characters
         * in the input.
         * State 6 is the error state; an error has been detected
         * in the input and no future input can "fix" it.
         */
        private int state;   // state number (0 to 6)
        private int value;

        final private int[] alphabet;

        public Decoder(int flags, byte[] output) {
            this.output = output;

            alphabet = ((flags & URL_SAFE) == 0) ? DECODE : DECODE_WEBSAFE;
            state = 0;
            value = 0;
        }

        /**
         * @return an overestimate for the number of bytes {@code
         * len} bytes could decode to.
         */
        public int maxOutputSize(int len) {
            return len * 3/4 + 10;
        }

        /**
         * Decode another block of input data.
         *
         * @return true if the state machine is still healthy.  false if
         *         bad base-64 data has been detected in the input stream.
         */
        public boolean process(byte[] input, int offset, int len, boolean finish) {
            if (this.state == 6) return false;

            int p = offset;
            len += offset;

            // Using local variables makes the decoder about 12%
            // faster than if we manipulate the member variables in
            // the loop.  (Even alphabet makes a measurable
            // difference, which is somewhat surprising to me since
            // the member variable is final.)
            int state = this.state;
            int value = this.value;
            int op = 0;
            final byte[] output = this.output;
            final int[] alphabet = this.alphabet;

            while (p < len) {
                // Try the fast path:  we're starting a new tuple and the
                // next four bytes of the input stream are all data
                // bytes.  This corresponds to going through states
                // 0-1-2-3-0.  We expect to use this method for most of
                // the data.
                //
                // If any of the next four bytes of input are non-data
                // (whitespace, etc.), value will end up negative.  (All
                // the non-data values in decode are small negative
                // numbers, so shifting any of them up and or'ing them
                // together will result in a value with its top bit set.)
                //
                // You can remove this whole block and the output should
                // be the same, just slower.
                if (state == 0) {
                    while (p+4 <= len &&
                           (value = ((alphabet[input[p] & 0xff] << 18) |
                                     (alphabet[input[p+1] & 0xff] << 12) |
                                     (alphabet[input[p+2] & 0xff] << 6) |
                                     (alphabet[input[p+3] & 0xff]))) >= 0) {
                        output[op+2] = (byte) value;
                        output[op+1] = (byte) (value >> 8);
                        output[op] = (byte) (value >> 16);
                        op += 3;
                        p += 4;
                    }
                    if (p >= len) break;
                }

                // The fast path isn't available -- either we've read a
                // partial tuple, or the next four input bytes aren't all
                // data, or whatever.  Fall back to the slower state
                // machine implementation.

                int d = alphabet[input[p++] & 0xff];

                switch (state) {
                case 0:
                    if (d >= 0) {
                        value = d;
                        ++state;
                    } else if (d != SKIP) {
                        this.state = 6;
                        return false;
                    }
                    break;

                case 1:
                    if (d >= 0) {
                        value = (value << 6) | d;
                        ++state;
                    } else if (d != SKIP) {
                        this.state = 6;
                        return false;
                    }
                    break;

                case 2:
                    if (d >= 0) {
                        value = (value << 6) | d;
                        ++state;
                    } else if (d == EQUALS) {
                        // Emit the last (partial) output tuple;
                        // expect exactly one more padding character.
                        output[op++] = (byte) (value >> 4);
                        state = 4;
                    } else if (d != SKIP) {
                        this.state = 6;
                        return false;
                    }
                    break;

                case 3:
                    if (d >= 0) {
                        // Emit the output triple and return to state 0.
                        value = (value << 6) | d;
                        output[op+2] = (byte) value;
                        output[op+1] = (byte) (value >> 8);
                        output[op] = (byte) (value >> 16);
                        op += 3;
                        state = 0;
                    } else if (d == EQUALS) {
                        // Emit the last (partial) output tuple;
                        // expect no further data or padding characters.
                        output[op+1] = (byte) (value >> 2);
                        output[op] = (byte) (value >> 10);
                        op += 2;
                        state = 5;
                    } else if (d != SKIP) {
                        this.state = 6;
                        return false;
                    }
                    break;

                case 4:
                    if (d == EQUALS) {
                        ++state;
                    } else if (d != SKIP) {
                        this.state = 6;
                        return false;
                    }
                    break;

                case 5:
                    if (d != SKIP) {
                        this.state = 6;
                        return false;
                    }
                    break;
                }
            }

            if (!finish) {
                // We're out of input, but a future call could provide
                // more.
                this.state = state;
                this.value = value;
                this.op = op;
                return true;
            }

            // Done reading input.  Now figure out where we are left in
            // the state machine and finish up.

            switch (state) {
            case 0:
                // Output length is a multiple of three.  Fine.
                break;
            case 1:
                // Read one extra input byte, which isn't enough to
                // make another output byte.  Illegal.
                this.state = 6;
                return false;
            case 2:
                // Read two extra input bytes, enough to emit 1 more
                // output byte.  Fine.
                output[op++] = (byte) (value >> 4);
                break;
            case 3:
                // Read three extra input bytes, enough to emit 2 more
                // output bytes.  Fine.
                output[op++] = (byte) (value >> 10);
                output[op++] = (byte) (value >> 2);
                break;
            case 4:
                // Read one padding '=' when we expected 2.  Illegal.
                this.state = 6;
                return false;
            case 5:
                // Read all the padding '='s we expected and no more.
                // Fine.
                break;
            }

            this.state = state;
            this.op = op;
            return true;
        }
    }

    //  --------------------------------------------------------
    //  encoding
    //  --------------------------------------------------------

    /**
     * Base64-encode the given data and return a newly allocated
     * String with the result.
     *
     * @param input  the data to encode
     * @param flags  controls certain features of the encoded output.
     *               Passing {@code DEFAULT} results in output that
     *               adheres to RFC 2045.
     */
    public static String encodeToString(byte[] input, int flags) {
        try {
            return new String(encode(input, flags), "US-ASCII");
        } catch (UnsupportedEncodingException e) {
            // US-ASCII is guaranteed to be available.
            throw new AssertionError(e);
        }
    }

    /**
     * Base64-encode the given data and return a newly allocated
     * String with the result.
     *
     * @param input  the data to encode
     * @param offset the position within the input array at which to
     *               start
     * @param len    the number of bytes of input to encode
     * @param flags  controls certain features of the encoded output.
     *               Passing {@code DEFAULT} results in output that
     *               adheres to RFC 2045.
     */
    public static String encodeToString(byte[] input, int offset, int len, int flags) {
        try {
            return new String(encode(input, offset, len, flags), "US-ASCII");
        } catch (UnsupportedEncodingException e) {
            // US-ASCII is guaranteed to be available.
            throw new AssertionError(e);
        }
    }

    /**
     * Base64-encode the given data and return a newly allocated
     * byte[] with the result.
     *
     * @param input  the data to encode
     * @param flags  controls certain features of the encoded output.
     *               Passing {@code DEFAULT} results in output that
     *               adheres to RFC 2045.
     */
    public static byte[] encode(byte[] input, int flags) {
        return encode(input, 0, input.length, flags);
    }

    /**
     * Base64-encode the given data and return a newly allocated
     * byte[] with the result.
     *
     * @param input  the data to encode
     * @param offset the position within the input array at which to
     *               start
     * @param len    the number of bytes of input to encode
     * @param flags  controls certain features of the encoded output.
     *               Passing {@code DEFAULT} results in output that
     *               adheres to RFC 2045.
     */
    public static byte[] encode(byte[] input, int offset, int len, int flags) {
        Encoder encoder = new Encoder(flags, null);

        // Compute the exact length of the array we will produce.
        int output_len = len / 3 * 4;

        // Account for the tail of the data and the padding bytes, if any.
        if (encoder.do_padding) {
            if (len % 3 > 0) {
                output_len += 4;
            }
        } else {
            switch (len % 3) {
                case 0: break;
                case 1: output_len += 2; break;
                case 2: output_len += 3; break;
            }
        }

        // Account for the newlines, if any.
        if (encoder.do_newline && len > 0) {
            output_len += (((len-1) / (3 * Encoder.LINE_GROUPS)) + 1) *
                (encoder.do_cr ? 2 : 1);
        }

        encoder.output = new byte[output_len];
        encoder.process(input, offset, len, true);

        assert encoder.op == output_len;

        return encoder.output;
    }

    /* package */ static class Encoder extends Coder {
        /**
         * Emit a new line every this many output tuples.  Corresponds to
         * a 76-character line length (the maximum allowable according to
         * <a href="http://www.ietf.org/rfc/rfc2045.txt">RFC 2045</a>).
         */
        public static final int LINE_GROUPS = 19;

        /**
         * Lookup table for turning Base64 alphabet positions (6 bits)
         * into output bytes.
         */
        private static final byte ENCODE[] = {
            'A', 'B', 'C', 'D', 'E', 'F', 'G', 'H', 'I', 'J', 'K', 'L', 'M', 'N', 'O', 'P',
            'Q', 'R', 'S', 'T', 'U', 'V', 'W', 'X', 'Y', 'Z', 'a', 'b', 'c', 'd', 'e', 'f',
            'g', 'h', 'i', 'j', 'k', 'l', 'm', 'n', 'o', 'p', 'q', 'r', 's', 't', 'u', 'v',
            'w', 'x', 'y', 'z', '0', '1', '2', '3', '4', '5', '6', '7', '8', '9', '+', '/',
        };

        /**
         * Lookup table for turning Base64 alphabet positions (6 bits)
         * into output bytes.
         */
        private static final byte ENCODE_WEBSAFE[] = {
            'A', 'B', 'C', 'D', 'E', 'F', 'G', 'H', 'I', 'J', 'K', 'L', 'M', 'N', 'O', 'P',
            'Q', 'R', 'S', 'T', 'U', 'V', 'W', 'X', 'Y', 'Z', 'a', 'b', 'c', 'd', 'e', 'f',
            'g', 'h', 'i', 'j', 'k', 'l', 'm', 'n', 'o', 'p', 'q', 'r', 's', 't', 'u', 'v',
            'w', 'x', 'y', 'z', '0', '1', '2', '3', '4', '5', '6', '7', '8', '9', '-', '_',
        };

        final private byte[] tail;
        /* package */ int tailLen;
        private int count;

        final public boolean do_padding;
        final public boolean do_newline;
        final public boolean do_cr;
        final private byte[] alphabet;

        public Encoder(int flags, byte[] output) {
            this.output = output;

            do_padding = (flags & NO_PADDING) == 0;
            do_newline = (flags & NO_WRAP) == 0;
            do_cr = (flags & CRLF) != 0;
            alphabet = ((flags & URL_SAFE) == 0) ? ENCODE : ENCODE_WEBSAFE;

            tail = new byte[2];
            tailLen = 0;

            count = do_newline ? LINE_GROUPS : -1;
        }

        /**
         * @return an overestimate for the number of bytes {@code
         * len} bytes could encode to.
         */
        public int maxOutputSize(int len) {
            return len * 8/5 + 10;
        }

        public boolean process(byte[] input, int offset, int len, boolean finish) {
            // Using local variables makes the encoder about 9% faster.
            final byte[] alphabet = this.alphabet;
            final byte[] output = this.output;
            int op = 0;
            int count = this.count;

            int p = offset;
            len += offset;
            int v = -1;

            // First we need to concatenate the tail of the previous call
            // with any input bytes available now and see if we can empty
            // the tail.

            switch (tailLen) {
                case 0:
                    // There was no tail.
                    break;

                case 1:
                    if (p+2 <= len) {
                        // A 1-byte tail with at least 2 bytes of
                        // input available now.
                        v = ((tail[0] & 0xff) << 16) |
                            ((input[p++] & 0xff) << 8) |
                            (input[p++] & 0xff);
                        tailLen = 0;
                    };
                    break;

                case 2:
                    if (p+1 <= len) {
                        // A 2-byte tail with at least 1 byte of input.
                        v = ((tail[0] & 0xff) << 16) |
                            ((tail[1] & 0xff) << 8) |
                            (input[p++] & 0xff);
                        tailLen = 0;
                    }
                    break;
            }

            if (v != -1) {
                output[op++] = alphabet[(v >> 18) & 0x3f];
                output[op++] = alphabet[(v >> 12) & 0x3f];
                output[op++] = alphabet[(v >> 6) & 0x3f];
                output[op++] = alphabet[v & 0x3f];
                if (--count == 0) {
                    if (do_cr) output[op++] = '\r';
                    output[op++] = '\n';
                    count = LINE_GROUPS;
                }
            }

            // At this point either there is no tail, or there are fewer
            // than 3 bytes of input available.

            // The main loop, turning 3 input bytes into 4 output bytes on
            // each iteration.
            while (p+3 <= len) {
                v = ((input[p] & 0xff) << 16) |
                    ((input[p+1] & 0xff) << 8) |
                    (input[p+2] & 0xff);
                output[op] = alphabet[(v >> 18) & 0x3f];
                output[op+1] = alphabet[(v >> 12) & 0x3f];
                output[op+2] = alphabet[(v >> 6) & 0x3f];
                output[op+3] = alphabet[v & 0x3f];
                p += 3;
                op += 4;
                if (--count == 0) {
                    if (do_cr) output[op++] = '\r';
                    output[op++] = '\n';
                    count = LINE_GROUPS;
                }
            }

            if (finish) {
                // Finish up the tail of the input.  Note that we need to
                // consume any bytes in tail before any bytes
                // remaining in input; there should be at most two bytes
                // total.

                if (p-tailLen == len-1) {
                    int t = 0;
                    v = ((tailLen > 0 ? tail[t++] : input[p++]) & 0xff) << 4;
                    tailLen -= t;
                    output[op++] = alphabet[(v >> 6) & 0x3f];
                    output[op++] = alphabet[v & 0x3f];
                    if (do_padding) {
                        output[op++] = '=';
                        output[op++] = '=';
                    }
                    if (do_newline) {
                        if (do_cr) output[op++] = '\r';
                        output[op++] = '\n';
                    }
                } else if (p-tailLen == len-2) {
                    int t = 0;
                    v = (((tailLen > 1 ? tail[t++] : input[p++]) & 0xff) << 10) |
                        (((tailLen > 0 ? tail[t++] : input[p++]) & 0xff) << 2);
                    tailLen -= t;
                    output[op++] = alphabet[(v >> 12) & 0x3f];
                    output[op++] = alphabet[(v >> 6) & 0x3f];
                    output[op++] = alphabet[v & 0x3f];
                    if (do_padding) {
                        output[op++] = '=';
                    }
                    if (do_newline) {
                        if (do_cr) output[op++] = '\r';
                        output[op++] = '\n';
                    }
                } else if (do_newline && op > 0 && count != LINE_GROUPS) {
                    if (do_cr) output[op++] = '\r';
                    output[op++] = '\n';
                }

                assert tailLen == 0;
                assert p == len;
            } else {
                // Save the leftovers in tail to be consumed on the next
                // call to encodeInternal.

                if (p == len-1) {
                    tail[tailLen++] = input[p];
                } else if (p == len-2) {
                    tail[tailLen++] = input[p];
                    tail[tailLen++] = input[p+1];
                }
            }

            this.op = op;
            this.count = count;

            return true;
        }
    }

    private Base64() { }   // don't instantiate
}






}


