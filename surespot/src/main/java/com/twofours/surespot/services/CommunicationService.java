package com.twofours.surespot.services;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.support.v4.app.NotificationCompat;
import android.support.v4.app.TaskStackBuilder;
import android.text.TextUtils;
import android.util.Log;

import com.twofours.surespot.R;
import com.twofours.surespot.SurespotApplication;
import com.twofours.surespot.Tuple;
import com.twofours.surespot.activities.MainActivity;
import com.twofours.surespot.chat.ChatAdapter;
import com.twofours.surespot.chat.ChatUtils;
import com.twofours.surespot.chat.SurespotControlMessage;
import com.twofours.surespot.chat.SurespotErrorMessage;
import com.twofours.surespot.chat.SurespotMessage;
import com.twofours.surespot.common.FileUtils;
import com.twofours.surespot.common.SurespotConfiguration;
import com.twofours.surespot.common.SurespotConstants;
import com.twofours.surespot.common.SurespotLog;
import com.twofours.surespot.common.Utils;
import com.twofours.surespot.encryption.EncryptionController;
import com.twofours.surespot.identity.IdentityController;
import com.twofours.surespot.images.FileCacheController;
import com.twofours.surespot.images.MessageImageDownloader;
import com.twofours.surespot.network.CookieResponseHandler;
import com.twofours.surespot.network.MainThreadCallbackWrapper;
import com.twofours.surespot.network.NetworkController;
import com.twofours.surespot.network.NetworkHelper;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ConcurrentLinkedQueue;

import io.socket.client.IO;
import io.socket.client.Manager;
import io.socket.client.Socket;
import io.socket.emitter.Emitter;
import io.socket.engineio.client.EngineIOException;
import io.socket.engineio.client.Transport;
import io.socket.engineio.client.transports.WebSocket;
import okhttp3.Call;
import okhttp3.Cookie;
import okhttp3.Response;


@SuppressLint("NewApi")
public class CommunicationService extends Service {
    private static final String TAG = "CommunicationService";

    private final IBinder mBinder = new CommunicationServiceBinder();
    private ITransmissionServiceListener mListener;
    private ConcurrentLinkedQueue<SurespotMessage> mSendQueue = new ConcurrentLinkedQueue<SurespotMessage>();

    private BroadcastReceiver mConnectivityReceiver;
    private String mUsername;
    private static boolean mMainActivityPaused = false;

    private ReconnectTask mReconnectTask;
    Handler mHandler = new Handler(Looper.getMainLooper());

    public static final int STATE_CONNECTING = 2;
    public static final int STATE_CONNECTED = 1;
    public static final int STATE_DISCONNECTED = 0;
    private static final int MAX_RETRIES = 60;

    // maximum time before reconnecting in seconds
    private static final int MAX_RETRY_DELAY = 10;

    private int mHttpResendTries = 0;
    private Socket mSocket;
    private int mSocketReconnectRetries = 0;
    private Timer mResendViaHttpTimer;
    private Timer mBackgroundTimer;
    private int mConnectionState;
    private NotificationManager mNotificationManager;
    private NotificationCompat.Builder mBuilder;
    private String mCurrentSendIv;
    private ProcessNextMessageTask mResendTask;
    private boolean mErrored;


    @Override
    public void onCreate() {
        SurespotLog.i(TAG, "onCreate");
        mConnectivityReceiver = new BroadcastReceiverHandler();
        mNotificationManager = (NotificationManager) CommunicationService.this.getSystemService(Context.NOTIFICATION_SERVICE);
        mBuilder = new NotificationCompat.Builder(CommunicationService.this);
    }

    private synchronized void disposeSocket() {
        SurespotLog.d(TAG, "disposeSocket");
        if (mSocket != null) {
            mSocket.off(Socket.EVENT_CONNECT);
            mSocket.off(Socket.EVENT_DISCONNECT);
            mSocket.off(Socket.EVENT_ERROR);
            mSocket.off(Socket.EVENT_CONNECT_ERROR);
            mSocket.off(Socket.EVENT_CONNECT_TIMEOUT);
            mSocket.off(Socket.EVENT_MESSAGE);
            mSocket.off("messageError");
            mSocket.off("control");
            mSocket.io().off(Manager.EVENT_TRANSPORT);
            mSocket = null;
        }
    }

    private Socket createSocket() {
        SurespotLog.d(TAG, "createSocket, mSocket == null: %b", mSocket == null);
        if (mSocket == null) {
            IO.Options opts = new IO.Options();

            //override ssl context for self signed certs for dev
            if (!SurespotConfiguration.isSslCheckingStrict()) {
                opts.sslContext = SurespotApplication.getNetworkController().getSSLContext();
                opts.hostnameVerifier = SurespotApplication.getNetworkController().getHostnameVerifier();
            }

            opts.reconnection = false;
            opts.transports = new String[]{WebSocket.NAME};

            try {
                mSocket = IO.socket(SurespotConfiguration.getBaseUrl(), opts);
            }
            catch (URISyntaxException e) {
                mSocket = null;
                return null;
            }

            mSocket.on(Socket.EVENT_CONNECT, onConnect);
            mSocket.on(Socket.EVENT_DISCONNECT, onDisconnect);
            mSocket.on(Socket.EVENT_ERROR, onConnectError);
            mSocket.on(Socket.EVENT_CONNECT_ERROR, onConnectError);
            mSocket.on(Socket.EVENT_CONNECT_TIMEOUT, onConnectError);
            mSocket.on(Socket.EVENT_MESSAGE, onMessage);
            mSocket.on("messageError", onMessageError);
            mSocket.on("control", onControl);
            mSocket.io().on(Manager.EVENT_TRANSPORT, new Emitter.Listener() {
                @Override
                public void call(Object... args) {

                    Transport transport = (Transport) args[0];
                    SurespotLog.d(TAG, "socket.io EVENT_TRANSPORT");
                    transport.on(Transport.EVENT_REQUEST_HEADERS, new Emitter.Listener() {
                        @Override
                        public void call(Object... args) {
                            SurespotLog.d(TAG, "socket.io EVENT_REQUEST_HEADERS");
                            @SuppressWarnings("unchecked")
                            Map<String, List> headers = (Map<String, List>) args[0];
                            // set header
                            Cookie cookie = IdentityController.getCookieForUser(mUsername);
                            if (cookie != null) {
                                ArrayList<String> cookies = new ArrayList<String>();
                                cookies.add(cookie.name() + "=" + cookie.value());
                                headers.put("cookie", cookies);
                            }
                        }
                    });
                }
            });
        }
        return mSocket;
    }

    // sets if the main activity is paused or not
    public void setMainActivityPaused(boolean paused) {
        mMainActivityPaused = paused;
        if (paused) {
            save();
            disconnect();
        }
        else {
            connect();
        }
    }

    // Notify the service that the user logged out
    public void userLoggedOut() {
        if (mUsername != null) {
            SurespotLog.d(TAG, "user logging out: " + mUsername);

            save();
            mUsername = null;
            shutdownConnection();
            mSendQueue.clear();
            SurespotApplication.getChatController().dispose();
        }
    }

    public synchronized boolean connect(String username) {
        if (!username.equals(mUsername)) {
            SurespotLog.d(TAG, "Setting user name to " + username + " and connecting");
            //don't need to disconnect 1st time through when mUsername will be null
            if (mUsername != null) {
                disconnect();
            }
            mUsername = username;
            loadMessageQueue();
        }

        return connect();
    }

    public synchronized boolean connect() {

        if (mMainActivityPaused) {
            // if the communication service wants to stay connected again any time in the future, disable the below statement
            return true;
        }

        SurespotLog.d(TAG, "connect, mSocket: " + mSocket + ", connected: " + (mSocket != null ? mSocket.connected() : false) + ", state: " + mConnectionState);

        if (getConnectionState() == STATE_CONNECTED && mSocket != null && mSocket.connected()) {
            onConnected();
            return true;
        }

        if (getConnectionState() == STATE_CONNECTING) {
            // do NOT call already connected here, since we're not already connected
            // need to test to see if the program flow is good returning true here, or if we should allow things to continue
            // and try to connect()...
            return true;
        }

        setState(STATE_CONNECTING);

        SurespotApplication.getChatController().onBeforeConnect();

        if (getConnectionState() == STATE_CONNECTED) {
            onConnected();
            return true;
        }

        try {
            createSocket();
            mSocket.connect();
        }
        catch (Exception e) {
            SurespotLog.w(TAG, e, "connect");
        }

        return false;
    }

    public void enqueueMessage(SurespotMessage message) {
        if (getConnectionState() == STATE_DISCONNECTED) {
            connect();
        }

        if (!mSendQueue.contains(message)) {
            mSendQueue.add(message);
        }
    }

    public synchronized void processNextMessage() {

        //if we're ERRORED do nothing
        if (mErrored) {
            SurespotLog.d(TAG, "processNextMessage in ERRORED state, doing nothing");
            return;
        }

        SurespotLog.d(TAG, "processNextMessage, messages in queue: %d", mSendQueue.size());
        SurespotMessage nextMessage = mSendQueue.peek();
        //if the message is errored don't resend it, remove from queue
        while (nextMessage != null && nextMessage.getErrorStatus() > 0) {
            SurespotLog.d(TAG, "processNextMessage, removing errored message: %s", nextMessage.getIv());
            removeQueuedMessage(nextMessage);
            nextMessage = mSendQueue.peek();
        }

        if (nextMessage != null) {
            SurespotLog.d(TAG, "processNextMessage, currentIv: %s, next message iv: %s", mCurrentSendIv, nextMessage.getIv());
            if (mCurrentSendIv == nextMessage.getIv()) {
                SurespotLog.i(TAG, "processNextMessage() still sending message, iv: %s", nextMessage.getIv());
            }
            else {
                mCurrentSendIv = nextMessage.getIv();

                //message processed successfully, onto the next
                SurespotLog.i(TAG, "processNextMessage() sending message, iv: %s", nextMessage.getIv());

                switch (nextMessage.getMimeType()) {
                    case SurespotConstants.MimeTypes.TEXT:
                        prepAndSendTextMessage(nextMessage);
                        break;
                    case SurespotConstants.MimeTypes.IMAGE:
                    case SurespotConstants.MimeTypes.M4A:
                        prepAndSendFileMessage(nextMessage);
                        break;
                }
            }
        }
    }

    private boolean isMessageReadyToSend(SurespotMessage message) {
        return !TextUtils.isEmpty(message.getData()) && !TextUtils.isEmpty(message.getFromVersion()) && !TextUtils.isEmpty(message.getToVersion());
    }


    private synchronized void prepAndSendTextMessage(final SurespotMessage message) {
        SurespotLog.d(TAG, "prepAndSendTextMessage, iv: %s", message.getIv());

        //make sure message is encrypted
        if (!isMessageReadyToSend(message)) {
            // do encryption in background
            new AsyncTask<Void, Void, Boolean>() {

                @Override
                protected Boolean doInBackground(Void... arg0) {
                    String ourLatestVersion = IdentityController.getOurLatestVersion(message.getFrom());
                    String theirLatestVersion = IdentityController.getTheirLatestVersion(message.getTo());

                    if (theirLatestVersion == null) {
                        SurespotLog.d(TAG, "could not encrypt message - could not get latest version, iv: %s", message.getIv());
                        //retry
                        message.setErrorStatus(0);
                        return false;
                    }

                    byte[] iv = ChatUtils.base64DecodeNowrap(message.getIv());
                    String result = EncryptionController.symmetricEncrypt(ourLatestVersion, message.getTo(), theirLatestVersion, message.getPlainData().toString(), iv);

                    if (result != null) {
                        //update unsent message
                        message.setData(result);
                        message.setFromVersion(ourLatestVersion);
                        message.setToVersion(theirLatestVersion);
                        return true;
                    }
                    else {
                        SurespotLog.d(TAG, "could not encrypt message, iv: %s", message.getIv());
                        message.setErrorStatus(500);
                        return false;
                    }
                }

                protected void onPostExecute(Boolean success) {
                    SurespotApplication.getChatController().addMessage(message);
                    if (success) {
                        sendTextMessage(message);
                    }
                    else {
                        messageSendCompleted(message);
                        if (!scheduleResendTimer()) {
                            errorMessageQueue();
                        }
                    }
                }
            }.execute();
        }
        else {
            sendTextMessage(message);
        }
    }

    private void sendTextMessage(SurespotMessage message) {
        if (getConnectionState() == STATE_CONNECTED) {
            SurespotLog.d(TAG, "sendTextMessage, mSocket: %s", mSocket);
            JSONObject json = message.toJSONObjectSocket();
            SurespotLog.d(TAG, "sendTextMessage, json: %s", json);
            //String s = json.toString();
            //SurespotLog.d(TAG, "sendmessage, message string: %s", s);

            if (mSocket != null) {
                mSocket.send(json);
            }
        }
        else {
            sendMessageUsingHttp(message);
        }
    }

    private void prepAndSendFileMessage(final SurespotMessage message) {
        if (!isMessageReadyToSend(message)) {


            new AsyncTask<Void, Void, Boolean>() {

                @Override
                protected Boolean doInBackground(Void... arg0) {
                    //make sure it's pointing to a local file
                    if (message.getPlainData() == null || !message.getPlainData().toString().startsWith("file")) {
                        message.setErrorStatus(500);
                        return false;
                    }

                    try {

                        final String ourVersion = IdentityController.getOurLatestVersion(message.getFrom());
                        final String theirVersion = IdentityController.getTheirLatestVersion(message.getTo());
                        if (theirVersion == null) {
                            SurespotLog.d(TAG, "could not encrypt file  message - could not get latest version, iv: %s", message.getIv());
                            //retry
                            message.setErrorStatus(0);
                            return false;
                        }
                        final String iv = message.getIv();


                        // save encrypted image to disk
                        InputStream fileInputStream = CommunicationService.this.getContentResolver().openInputStream(Uri.parse(message.getPlainData().toString()));
                        File localImageFile = ChatUtils.getTempImageUploadFile(CommunicationService.this);
                        OutputStream fileSaveStream = new FileOutputStream(localImageFile);
                        String localImageUri = Uri.fromFile(localImageFile).toString();
                        SurespotLog.d(TAG, "encrypting file iv: %s, from %s to encrypted file %s", iv, message.getPlainData().toString(), localImageUri);

                        //encrypt
                        PipedOutputStream encryptionOutputStream = new PipedOutputStream();
                        final PipedInputStream encryptionInputStream = new PipedInputStream(encryptionOutputStream);
                        EncryptionController.runEncryptTask(ourVersion, message.getTo(), theirVersion, iv, new BufferedInputStream(fileInputStream), encryptionOutputStream);

                        int bufferSize = 1024;
                        byte[] buffer = new byte[bufferSize];

                        int len = 0;
                        while ((len = encryptionInputStream.read(buffer)) != -1) {
                            fileSaveStream.write(buffer, 0, len);
                        }
                        fileSaveStream.close();
                        encryptionInputStream.close();

                        //move bitmap cache
                        if (message.getMimeType().equals(SurespotConstants.MimeTypes.IMAGE)) {
                            MessageImageDownloader.moveCacheEntry(message.getPlainData().toString(), localImageUri);
                        }

                        //add encrypted local file to file cache
                        FileCacheController fcc = SurespotApplication.getFileCacheController();
                        if (fcc != null) {
                            fcc.putEntry(localImageUri, new FileInputStream(localImageFile));
                        }


                        boolean deleted = new File(Uri.parse(message.getPlainData().toString()).getPath()).delete();
                        SurespotLog.d(TAG, "deleting unencrypted file %s, iv: %s, success: %b", message.getPlainData().toString(), iv, deleted);

                        message.setPlainData(null);
                        message.setData(localImageUri);
                        message.setFromVersion(ourVersion);
                        message.setToVersion(theirVersion);

                        return true;
                    }
                    catch (IOException e) {
                        SurespotLog.w(TAG, e, "prepAndSendFileMessage");
                        message.setErrorStatus(500);
                        return false;
                    }

                }

                protected void onPostExecute(Boolean success) {
                    SurespotApplication.getChatController().addMessage(message);
                    if (success) {
                        sendFileMessage(message);
                    }
                    else {
                        messageSendCompleted(message);
                        if (!scheduleResendTimer()) {
                            errorMessageQueue();
                        }
                    }
                }
            }.execute();
        }
        else {
            sendFileMessage(message);
        }
    }


    private void sendFileMessage(final SurespotMessage message) {
        SurespotLog.d(TAG, "sendFileMessage, iv: %s", message.getIv());
        new AsyncTask<Void, Void, Tuple<Integer, JSONObject>>() {
            @Override
            protected Tuple<Integer, JSONObject> doInBackground(Void... voids) {
                //post message via http if we have network controller for the from user
                NetworkController networkController = SurespotApplication.getNetworkController();
                if (networkController != null && message.getFrom().equals(networkController.getUsername())) {

                    FileInputStream uploadStream;
                    try {
                        uploadStream = new FileInputStream(URI.create(message.getData()).getPath());
                        return networkController.postFileStreamSync(
                                message.getOurVersion(),
                                message.getTo(),
                                message.getTheirVersion(),
                                message.getIv(),
                                uploadStream,
                                message.getMimeType());

                    }
                    catch (FileNotFoundException e) {
                        SurespotLog.w(TAG, e, "sendFileMessage");
                        return new Tuple<>(500, null);
                    }
                    catch (JSONException e) {
                        SurespotLog.w(TAG, e, "sendFileMessage");
                        return new Tuple<>(500, null);
                    }
                }
                else {
                    SurespotLog.i(TAG, "network controller null or different user");
                    return new Tuple<>(500, null);
                }
            }

            @Override
            protected void onPostExecute(Tuple<Integer, JSONObject> result) {
                synchronized (CommunicationService.this) {
                    messageSendCompleted(message);

                    //if message errored
                    int status = result.first;
                    //409 on duplicate, treat as success
                    if (status != 200 && status != 409) {
                        //try and send next message again
                        if (!scheduleResendTimer()) {
                            errorMessageQueue();
                        }
                    }
                    else {
                        //success
                        mErrored = false;
                        SurespotLog.d(TAG, "sendFileMessage received response: %s", result.second);
                        //need to remove the message from the queue before setting the current send iv to null
                        removeQueuedMessage(message);
                        processNextMessage();
                    }
                }
            }
        }.execute();
    }

    private void sendMessageUsingHttp(final SurespotMessage message) {
        SurespotLog.d(TAG, "sendMessagesUsingHttp, iv: %s", message.getIv());
        ArrayList<SurespotMessage> toSend = new ArrayList<SurespotMessage>();
        toSend.add(message);
        SurespotApplication.getNetworkController().postMessages(toSend, new MainThreadCallbackWrapper(new MainThreadCallbackWrapper.MainThreadCallback() {

            @Override
            public void onFailure(Call call, IOException e) {
                messageSendCompleted(message);

                SurespotLog.w(TAG, e, "sendMessagesUsingHttp onFailure");
                //try and send next message again
                if (!scheduleResendTimer()) {
                    errorMessageQueue();
                }
            }

            @Override
            public void onResponse(Call call, Response response, String responseString) throws IOException {
                messageSendCompleted(message);


                if (response.isSuccessful()) {
                    mErrored = false;
                    try {
                        JSONObject json = new JSONObject(responseString);
                        JSONArray messages = json.getJSONArray("messageStatus");
                        JSONObject messageAndStatus = messages.getJSONObject(0);
                        JSONObject jsonMessage = messageAndStatus.getJSONObject("message");
                        int status = messageAndStatus.getInt("status");

                        if (status == 204) {
                            SurespotMessage messageReceived = SurespotMessage.toSurespotMessage(jsonMessage);
                            //need to remove the message from the queue before setting the current send iv to null
                            removeQueuedMessage(messageReceived);
                            processNextMessage();
                        }
                        else {
                            //try and send next message again
                            if (!scheduleResendTimer()) {
                                errorMessageQueue();
                            }
                        }
                    }
                    catch (JSONException e) {
                        SurespotLog.w(TAG, e, "JSON received from server");
                        //try and send next message again
                        if (!scheduleResendTimer()) {
                            errorMessageQueue();
                        }
                    }

                }
                else {
                    SurespotLog.w(TAG, "sendMessagesUsingHttp response error code: %d", response.code());
                    //try and send next message again
                    if (!scheduleResendTimer()) {
                        errorMessageQueue();
                    }
                }
            }
        }));
    }

    public synchronized void messageSendCompleted(SurespotMessage message) {
        //if we're not onto a different message, set the current message pointer to null

        if (message.getIv().equals(mCurrentSendIv)) {
            SurespotLog.d(TAG, "messageSendCompleted iv's the same, setting to null, mCurrentSendIv: %s, messageIv: %s", mCurrentSendIv, message.getIv());
            mCurrentSendIv = null;
        }
        else {
            SurespotLog.d(TAG, "messageSendCompleted iv's not the same, doing nothing, mCurrentSendIv: %s, messageIv: %s", mCurrentSendIv, message.getIv());
        }

    }

    public int getConnectionState() {
        return mConnectionState;
    }

    public ConcurrentLinkedQueue<SurespotMessage> getSendQueue() {
        return mSendQueue;
    }


    // saves all data and current state for user, general
    public synchronized void save() {
        SurespotLog.d(TAG, "save");
        saveFriends();
        saveMessages();
        saveMessageQueue();

        if (SurespotApplication.getChatController() != null) {
            SurespotLog.d(TAG, "saving last chat: %s", SurespotApplication.getChatController().getCurrentChat());
            Utils.putSharedPrefsString(this, SurespotConstants.PrefNames.LAST_CHAT, SurespotApplication.getChatController().getCurrentChat());
        }

        if (mSendQueue.size() == 0) {
            FileUtils.wipeFileUploadDir(this);
        }
    }

    public void clearServiceListener() {
        mListener = null;
    }

    private void saveIfMainActivityPaused() {
        if (mMainActivityPaused) {
            save();
        }
    }

    public boolean isConnected() {
        return getConnectionState() == CommunicationService.STATE_CONNECTED;
    }

    public void errorMessageQueue() {
        SurespotLog.d(TAG, "errorMessageQueue");

        saveMessageQueue();
        saveMessages();

        //notify UI
        if (mListener != null) {
            mListener.onCouldNotConnectToServer();
        }


        // raise Android notifications for unsent messages so the user can re-enter the app and retry sending if we haven't already
        if (!mErrored && !CommunicationService.this.mSendQueue.isEmpty()) {
            raiseNotificationForUnsentMessages();
        }

        //cancel timers
        stopReconnectionAttempts();
        stopResendTimer();

        mErrored = true;
        mCurrentSendIv = null;
    }

    public void clearMessageQueue(String friendname) {
        Iterator<SurespotMessage> iterator = mSendQueue.iterator();
        while (iterator.hasNext()) {
            SurespotMessage message = iterator.next();
            if (message.getTo().equals(friendname)) {
                iterator.remove();
            }
        }
    }

    public void removeQueuedMessage(SurespotMessage message) {
        boolean removed = false;

        Iterator<SurespotMessage> iterator = mSendQueue.iterator();
        while (iterator.hasNext()) {
            SurespotMessage m = iterator.next();
            if (m.getIv().equals(message.getIv())) {
                iterator.remove();
                removed = true;
            }
        }

        if (removed) {
            saveMessageQueue();
        }

        SurespotLog.d(TAG, "removedQueuedMessage, iv: %s, removed: %b", message.getIv(), removed);
    }


    public class CommunicationServiceBinder extends Binder {
        public CommunicationService getService() {
            return CommunicationService.this;
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // as long as the main activity isn't forced to be destroyed right away, we don't really need to run as STICKY
        // At some point in the future if we want to poll the server for notifications, we may need to run as STICKY
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }

    @Override
    public void onDestroy() {
        SurespotLog.i(TAG, "onDestroy");
        unregisterReceiver();
        disposeSocket();
        save();
    }

    public void initializeService(ITransmissionServiceListener listener) {
        if (mConnectivityReceiver != null) {
            unregisterReceiver();
        }
        SurespotLog.d(TAG, "initializeService: ", this.getClass().getSimpleName());
        this.registerReceiver(mConnectivityReceiver, new IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION));
        mListener = listener;
    }

    // chat adapters and state

    private synchronized void saveMessages() {
        // save last 30? messages
        SurespotLog.d(TAG, "saveMessages");
        if (mUsername != null) {
            for (Map.Entry<String, ChatAdapter> entry : SurespotApplication.getChatController().mChatAdapters.entrySet()) {
                String them = entry.getKey();
                String spot = ChatUtils.getSpot(mUsername, them);
                SurespotApplication.getStateController().saveMessages(mUsername, spot, entry.getValue().getMessages(),
                        entry.getValue().getCurrentScrollPositionId());
            }
        }
    }

    public synchronized void saveMessages(String username) {
        // save last 30? messages
        SurespotLog.d(TAG, "saveMessages, username: %s", username);
        ChatAdapter chatAdapter = SurespotApplication.getChatController().mChatAdapters.get(username);

        if (chatAdapter != null) {
            SurespotApplication.getStateController().saveMessages(mUsername, ChatUtils.getSpot(mUsername, username), chatAdapter.getMessages(),
                    chatAdapter.getCurrentScrollPositionId());
        }
    }

    private void saveMessageQueue() {
        SurespotLog.d(TAG, "saving: " + mSendQueue.size() + " unsent messages.");
        SurespotApplication.getStateController().saveUnsentMessages(mUsername, mSendQueue);
    }

    private void loadMessageQueue() {
        mSendQueue.clear();
        Iterator<SurespotMessage> iterator = SurespotApplication.getStateController().loadUnsentMessages(mUsername).iterator();
        while (iterator.hasNext()) {
            SurespotMessage message = iterator.next();

            if (!mSendQueue.contains(message)) {
                mSendQueue.add(message);
            }

            //make sure the message is in the adapter so we can see it
            SurespotApplication.getChatController().addMessage(message);
        }
        SurespotLog.d(TAG, "loaded: " + mSendQueue.size() + " unsent messages.");
    }

    private void saveFriends() {
        if (SurespotApplication.getChatController() != null) {
            if (SurespotApplication.getChatController().getFriendAdapter() != null && SurespotApplication.getChatController().getFriendAdapter().getCount() > 0) {
                SurespotApplication.getChatController().saveFriends();
            }
        }
    }

    // notify listeners that we've connected
    private void onConnected() {
        SurespotLog.d(TAG, "onConnected");
        mErrored = false;

        stopReconnectionAttempts();
        stopResendTimer();


        // tell any listeners that we're connected
        if (mListener != null) {
            SurespotLog.d(TAG, "onConnected, mListener calling onConnected()");
            mListener.onConnected();
        }
        else {
            SurespotLog.d(TAG, "onConnected, mListener was null");
        }

        processNextMessage();
    }

    // notify listeners that we've connected
    private void onNotConnected() {
        // tell any listeners that we're connected
        if (mListener != null) {
            mListener.onNotConnected();
        }
    }


    // remove duplicate messages
    private List<SurespotMessage> removeDuplicates(List<SurespotMessage> messages) {
        ArrayList<SurespotMessage> messagesSeen = new ArrayList<SurespotMessage>();
        for (int i = messages.size() - 1; i >= 0; i--) {
            SurespotMessage message = messages.get(i);
            if (isMessageEqualToAny(message, messagesSeen)) {
                messages.remove(i);
                SurespotLog.d(TAG, "Prevented sending duplicate message: " + message.toString());
            }
            else {
                messagesSeen.add(message);
            }
        }
        return messages;
    }

    private boolean isMessageEqualToAny(SurespotMessage message, List<SurespotMessage> messages) {
        for (SurespotMessage msg : messages) {
            if (SurespotMessage.areMessagesEqual(msg, message)) {
                return true;
            }
        }
        return false;
    }

    private int generateInterval(int k) {
        int timerInterval = (int) (Math.pow(2, k) * 1000);
        if (timerInterval > MAX_RETRY_DELAY * 1000) {
            timerInterval = MAX_RETRY_DELAY * 1000;
        }

        int reconnectTime = (int) (Math.random() * timerInterval);
        SurespotLog.d(TAG, "generated reconnect time: %d for k: %d", reconnectTime, k);
        return reconnectTime;
    }

    // stop reconnection attempts
    private synchronized void stopReconnectionAttempts() {
        if (mBackgroundTimer != null) {
            mBackgroundTimer.cancel();
            mBackgroundTimer = null;
        }
        if (mReconnectTask != null) {
            boolean cancel = mReconnectTask.cancel();
            mReconnectTask = null;
            SurespotLog.d(TAG, "Cancelled reconnect task: " + cancel);
        }
        mSocketReconnectRetries = 0;
    }

    private synchronized void scheduleReconnectionAttempt() {
        int timerInterval = generateInterval(mSocketReconnectRetries++);
        SurespotLog.d(TAG, "reconnection timer try %d starting another task in: %d", mSocketReconnectRetries - 1, timerInterval);

        if (mReconnectTask != null) {
            mReconnectTask.cancel();
            mReconnectTask = null;
        }

        if (mBackgroundTimer != null) {
            mBackgroundTimer.cancel();
            mBackgroundTimer = null;
        }

        // Is there ever a case where we don't want to try a reconnect?
        ReconnectTask reconnectTask = new ReconnectTask();
        mBackgroundTimer = new Timer("backgroundTimer");
        mBackgroundTimer.schedule(reconnectTask, timerInterval);
        mReconnectTask = reconnectTask;
    }

    private synchronized boolean scheduleResendTimer() {
        SurespotLog.d(TAG, "scheduleResendTimer, mHttpResendTries: %d, MAX_RETRIES: %d", mHttpResendTries, MAX_RETRIES);

        if (mHttpResendTries++ < MAX_RETRIES) {
            int timerInterval = generateInterval(mHttpResendTries);
            SurespotLog.d(TAG, "resend timer try %d starting another task in: %d", mHttpResendTries - 1, timerInterval);


            if (mResendTask != null) {
                mResendTask.cancel();
                mResendTask = null;
            }


            if (mResendViaHttpTimer != null) {
                mResendViaHttpTimer.cancel();
                mResendViaHttpTimer = null;
            }


            // Is there ever a case where we don't want to try a reconnect?
            ProcessNextMessageTask reconnectTask = new ProcessNextMessageTask();
            mResendViaHttpTimer = new Timer("processNextMessageTimer");
            mResendViaHttpTimer.schedule(reconnectTask, timerInterval);
            mResendTask = reconnectTask;

            return true;
        }
        else {
            return false;
        }
    }

    private synchronized void stopResendTimer() {

        if (mResendViaHttpTimer != null) {
            mResendViaHttpTimer.cancel();
            mResendViaHttpTimer = null;
        }
        if (mResendTask != null) {
            boolean cancel = mResendTask.cancel();
            mResendTask = null;
            SurespotLog.d(TAG, "Cancelled resend task: " + cancel);
        }
        mHttpResendTries = 0;

    }


    // shutdown any connection we have open to the server, close sockets, check if service should shut down
    private void shutdownConnection() {
        disconnect();
        stopReconnectionAttempts();
        unregisterReceiver();
    }

    private void unregisterReceiver() {
        try {
            unregisterReceiver(mConnectivityReceiver);
        }
        catch (IllegalArgumentException e) {
            if (e.getMessage().contains("Receiver not registered")) {
                // Ignore this exception. This is exactly what is desired
            }
            else {
                // unexpected, re-throw
                throw e;
            }
        }
    }

    private synchronized void setState(int state) {
        mConnectionState = state;
    }

    private class ReconnectTask extends TimerTask {

        @Override
        public void run() {
            SurespotLog.d(TAG, "Reconnect task run.");
            connect();
        }
    }

    private class ProcessNextMessageTask extends TimerTask {

        @Override
        public void run() {
            SurespotLog.d(TAG, "ProcessNextMessage task run.");
            processNextMessage();
        }
    }


    @SuppressWarnings("ResourceAsColor")
    private void raiseNotificationForUnsentMessages() {
        mBuilder.setAutoCancel(true).setOnlyAlertOnce(true);
        SharedPreferences pm = null;
        if (mUsername != null) {
            pm = getSharedPreferences(mUsername, Context.MODE_PRIVATE);
        }

        int icon = R.drawable.surespot_logo;

        // need to use same builder for only alert once to work:
        // http://stackoverflow.com/questions/6406730/updating-an-ongoing-notification-quietly
        mBuilder.setSmallIcon(icon).setContentTitle(getString(R.string.error_sending_messages)).setAutoCancel(true).setOnlyAlertOnce(false).setContentText(getString(R.string.error_sending_detail));
        TaskStackBuilder stackBuilder = TaskStackBuilder.create(this);

        Intent mainIntent = null;
        mainIntent = new Intent(this, MainActivity.class);
        mainIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        mainIntent.putExtra(SurespotConstants.ExtraNames.UNSENT_MESSAGES, "true");
        mainIntent.putExtra(SurespotConstants.ExtraNames.NAME, mUsername);

        stackBuilder.addNextIntent(mainIntent);

        PendingIntent resultPendingIntent = stackBuilder.getPendingIntent((int) new Date().getTime(), PendingIntent.FLAG_CANCEL_CURRENT);

        mBuilder.setContentIntent(resultPendingIntent);
        int defaults = 0;

        boolean showLights = pm == null ? true : pm.getBoolean("pref_notifications_led", true);
        boolean makeSound = pm == null ? true : pm.getBoolean("pref_notifications_sound", true);
        boolean vibrate = pm == null ? true : pm.getBoolean("pref_notifications_vibration", true);
        int color = pm == null ? 0xff0000FF : pm.getInt("pref_notification_color", getResources().getColor(R.color.surespotBlue));

        if (showLights) {
            SurespotLog.v(TAG, "showing notification led");
            mBuilder.setLights(color, 500, 5000);
            defaults |= Notification.FLAG_SHOW_LIGHTS;
        }
        else {
            mBuilder.setLights(color, 0, 0);
        }

        if (makeSound) {
            SurespotLog.v(TAG, "making notification sound");
            defaults |= Notification.DEFAULT_SOUND;
        }

        if (vibrate) {
            SurespotLog.v(TAG, "vibrating notification");
            defaults |= Notification.DEFAULT_VIBRATE;
        }

        mBuilder.setDefaults(defaults);
        mNotificationManager.notify(SurespotConstants.ExtraNames.UNSENT_MESSAGES, SurespotConstants.IntentRequestCodes.UNSENT_MESSAGE_NOTIFICATION, mBuilder.build());

        // mNotificationManager.notify(tag, id, mBuilder.build());
        // Notification notification = UIUtils.generateNotification(mBuilder, contentIntent, getPackageName(), title, message);
        // mNotificationManager.notify(tag, id, notification);
    }


    private void disconnect() {

//        if (SurespotApplication.getChatController() != null) {
//            SurespotApplication.getChatController().onPause();
//        } else {
        //save();
        //}

        SurespotLog.d(TAG, "disconnect.");
        setState(STATE_DISCONNECTED);

        if (mSocket != null) {
            mSocket.disconnect();
            disposeSocket();
        }
    }


    private void tryReLogin() {
        SurespotLog.d(TAG, "trying to relogin " + mUsername);
        NetworkHelper.reLogin(CommunicationService.this, SurespotApplication.getNetworkController(), mUsername, new CookieResponseHandler() {
            private String TAG = "ReLoginCookieResponseHandler";

            @Override
            public void onSuccess(int responseCode, String result, Cookie cookie) {
                //try again
                disposeSocket();
                connect();
            }

            @Override
            public void onFailure(Throwable arg0, int code, String content) {


                //if we're getting 401 bail
                if (code == 401) {
                    // give up

                    disposeSocket();

                    if (mListener != null) {

                        SurespotLog.i(TAG, "401 on reconnect, giving up.");
                        mListener.on401();

                    }

                    userLoggedOut();
                }
                else {
                    //try and connect again
                    disposeSocket();
                    connect();
                }
            }
        });
    }

    private class BroadcastReceiverHandler extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            SurespotLog.d(TAG, "onReceive");
            debugIntent(intent, TAG);
            Bundle extras = intent.getExtras();
            if (extras.containsKey("networkInfo")) {
                NetworkInfo networkInfo2 = (NetworkInfo) extras.get("networkInfo");
                if (networkInfo2.getState() == NetworkInfo.State.CONNECTED) {
                    SurespotLog.d(TAG, "onReceive,  CONNECTED");
                    synchronized (CommunicationService.this) {
                        mErrored = false;
                        connect();
                        processNextMessage();
                    }
                    return;
                }
            }
        }
    }

    private void debugIntent(Intent intent, String tag) {
        Log.v(tag, "action: " + intent.getAction());
        Log.v(tag, "component: " + intent.getComponent());
        Bundle extras = intent.getExtras();
        if (extras != null) {
            for (String key : extras.keySet()) {
                Log.d(tag, "key [" + key + "]: " +
                        extras.get(key));
            }
        }
        else {
            Log.v(tag, "no extras");
        }
    }

    private Emitter.Listener onConnect = new Emitter.Listener() {
        @Override
        public void call(Object... args) {
            SurespotLog.d(TAG, "mSocket.io connection established");
            setState(STATE_CONNECTED);

            onConnected();

            if (SurespotApplication.getChatController() != null) {
                SurespotApplication.getChatController().onResume(true);
            }
        }
    };

    private Emitter.Listener onDisconnect = new Emitter.Listener() {
        @Override
        public void call(Object... args) {
            SurespotLog.d(TAG, "Connection terminated.");
            setState(STATE_DISCONNECTED);
            processNextMessage();
        }
    };

    private Emitter.Listener onConnectError = new Emitter.Listener() {
        @Override
        public void call(Object... args) {
            if (args.length > 0) {
                String reason = args[0].toString();
                if (args[0] instanceof EngineIOException) {
                    reason = ((EngineIOException) args[0]).getCause().toString();
                }
                SurespotLog.d(TAG, "onConnectError: args: %s", reason);
            }

            setState(STATE_DISCONNECTED);
            onNotConnected();


            if (args.length > 0) {
                if ("not authorized".equals(args[0])) {
                    SurespotLog.d(TAG, "got not authorized from websocket");
                    disposeSocket();
                    tryReLogin();
                    return;
                }
            }

            SurespotLog.i(TAG, "an Error occured, attempting reconnect with exponential backoff, retries: %d", mSocketReconnectRetries);

            // kick off another task
            if (!mMainActivityPaused && mSocketReconnectRetries < MAX_RETRIES) {
                scheduleReconnectionAttempt();
                //try and send messages via http
                processNextMessage();
            }
            else {
                SurespotLog.i(TAG, "Socket.io reconnect retries exhausted, giving up.");

                //mark all messages errored
                errorMessageQueue();
            }
        }
    };
    private Emitter.Listener onMessageError = new Emitter.Listener() {
        @Override
        public void call(final Object... args) {
            Runnable runnable = new Runnable() {
                @Override
                public void run() {
                    SurespotLog.d(TAG, "onMessageError, args: %s", args[0]);
                    try {
                        JSONObject jsonMessage = (JSONObject) args[0];
                        SurespotLog.d(TAG, "received messageError: " + jsonMessage.toString());
                        SurespotErrorMessage errorMessage = SurespotErrorMessage.toSurespotErrorMessage(jsonMessage);

                        //if the server says it errored we're fucked so don't bother trying to send it again
                        SurespotMessage message = null;
                        Iterator<SurespotMessage> iterator = mSendQueue.iterator();
                        while (iterator.hasNext()) {
                            message = iterator.next();
                            if (message.getIv().equals(errorMessage.getId())) {
                                iterator.remove();
                                message.setErrorStatus(errorMessage.getStatus());
                                break;
                            }
                        }

                        if (message != null) {
                            //update chat controller message
                            SurespotApplication.getChatController().addMessage(message);
                        }
                        processNextMessage();
                    }
                    catch (JSONException e) {
                        SurespotLog.w(TAG, "on messageError", e);
                    }
                }
            };

            mHandler.post(runnable);
        }

    };

    private Emitter.Listener onControl = new Emitter.Listener() {
        @Override
        public void call(final Object... args) {
            Runnable runnable = new Runnable() {
                @Override
                public void run() {
                    SurespotLog.d(TAG, "onControl, args: %s", args[0]);

                    try {
                        SurespotControlMessage message = SurespotControlMessage.toSurespotControlMessage((JSONObject) args[0]);
                        SurespotApplication.getChatController().handleControlMessage(null, message, true, false);
                    }
                    catch (JSONException e) {
                        SurespotLog.w(TAG, "on control", e);
                    }
                }
            };
            mHandler.post(runnable);
        }


    };

    private Emitter.Listener onMessage = new Emitter.Listener() {
        @Override
        public void call(final Object... args) {
            Runnable runnable = new Runnable() {
                @Override
                public void run() {
                    SurespotLog.d(TAG, "onMessage, args: %s", args[0]);
                    try {
                        JSONObject jsonMessage = (JSONObject) args[0];
                        SurespotLog.d(TAG, "received message: " + jsonMessage.toString());
                        SurespotMessage message = SurespotMessage.toSurespotMessage(jsonMessage);
                        SurespotApplication.getChatController().handleMessage(message);

                        // see if we have deletes
                        String sDeleteControlMessages = jsonMessage.optString("deleteControlMessages", null);
                        if (sDeleteControlMessages != null) {
                            JSONArray deleteControlMessages = new JSONArray(sDeleteControlMessages);

                            if (deleteControlMessages.length() > 0) {
                                for (int i = 0; i < deleteControlMessages.length(); i++) {
                                    try {
                                        SurespotControlMessage dMessage = SurespotControlMessage.toSurespotControlMessage(new JSONObject(deleteControlMessages.getString(i)));
                                        SurespotApplication.getChatController().handleControlMessage(null, dMessage, true, false);
                                    }
                                    catch (JSONException e) {
                                        SurespotLog.w(TAG, "on control", e);
                                    }
                                }
                            }
                        }

                        messageSendCompleted(message);
                        removeQueuedMessage(message);
                        saveIfMainActivityPaused();

                    }
                    catch (JSONException e) {
                        SurespotLog.w(TAG, "on message", e);
                    }
                    processNextMessage();
                }
            };

            mHandler.post(runnable);
        }
    };

    public static boolean isUIAttached() {
        return !mMainActivityPaused;
    }

}

