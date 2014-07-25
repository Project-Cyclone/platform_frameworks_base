/*
 * Copyright (C) 2014 The Android Open Source Project
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
 R* limitations under the License.
 */

package android.telecomm;

import android.app.PendingIntent;
import android.content.ComponentName;
import android.net.Uri;
import android.os.Handler;
import android.os.IBinder.DeathRecipient;
import android.os.Message;
import android.os.RemoteException;
import android.telephony.DisconnectCause;
import android.text.TextUtils;

import com.android.internal.os.SomeArgs;
import com.android.internal.telecomm.IConnectionService;
import com.android.internal.telecomm.IConnectionServiceAdapter;
import com.android.internal.telecomm.IVideoCallProvider;
import com.android.internal.telecomm.RemoteServiceCallback;

import java.util.LinkedList;
import java.util.List;
import java.util.UUID;

/**
 * Remote connection service which other connection services can use to place calls on their behalf.
 *
 * @hide
 */
final class RemoteConnectionService implements DeathRecipient {
    private static final int MSG_HANDLE_CREATE_CONNECTION_SUCCESSFUL = 1;
    private static final int MSG_HANDLE_CREATE_CONNECTION_FAILED = 2;
    private static final int MSG_HANDLE_CREATE_CONNECTION_CANCELLED = 3;
    private static final int MSG_SET_ACTIVE = 4;
    private static final int MSG_SET_RINGING = 5;
    private static final int MSG_SET_DIALING = 6;
    private static final int MSG_SET_DISCONNECTED = 7;
    private static final int MSG_SET_ON_HOLD = 8;
    private static final int MSG_SET_REQUESTING_RINGBACK = 9;
    private static final int MSG_SET_CALL_CAPABILITIES = 10;
    private static final int MSG_SET_IS_CONFERENCED = 11;
    private static final int MSG_ADD_CONFERENCE_CALL = 12;
    private static final int MSG_REMOVE_CALL = 13;
    private static final int MSG_ON_POST_DIAL_WAIT = 14;
    private static final int MSG_QUERY_REMOTE_CALL_SERVICES = 15;
    private static final int MSG_SET_VIDEO_STATE = 16;
    private static final int MSG_SET_CALL_VIDEO_PROVIDER = 17;
    private static final int MSG_SET_AUDIO_MODE_IS_VOIP = 18;
    private static final int MSG_SET_STATUS_HINTS = 19;
    private static final int MSG_SET_HANDLE = 20;
    private static final int MSG_SET_CALLER_DISPLAY_NAME = 21;
    private static final int MSG_START_ACTIVITY_FROM_IN_CALL = 22;

    private final IConnectionService mConnectionService;
    private final ComponentName mComponentName;

    private String mConnectionId;
    // Remote connection services only support a single connection.
    private RemoteConnection mConnection;

    private final Handler mHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_HANDLE_CREATE_CONNECTION_SUCCESSFUL: {
                    SomeArgs args = (SomeArgs) msg.obj;
                    try {
                        ConnectionRequest request = (ConnectionRequest) args.arg1;
                        if (isPendingConnection(request.getCallId())) {
                            ParcelableConnection parcel = (ParcelableConnection) args.arg2;
                            mConnection.setState(parcel.getState());
                            mConnection.setCallCapabilities(parcel.getCapabilities());
                            mConnection.setHandle(
                                    parcel.getHandle(), parcel.getHandlePresentation());
                            mConnection.setCallerDisplayName(
                                    parcel.getCallerDisplayName(),
                                    parcel.getCallerDisplayNamePresentation());
                            // TODO: Do we need to support video providers for remote connections?
                        }
                    } finally {
                        args.recycle();
                    }
                    break;
                }
                case MSG_HANDLE_CREATE_CONNECTION_FAILED: {
                    SomeArgs args = (SomeArgs) msg.obj;
                    try {
                        ConnectionRequest request = (ConnectionRequest) args.arg1;
                        if (isPendingConnection(request.getCallId())) {
                            // TODO: How do we propogate the failure codes?
                            destroyConnection();
                        }
                    } finally {
                        args.recycle();
                    }
                    break;
                }
                case MSG_HANDLE_CREATE_CONNECTION_CANCELLED: {
                    ConnectionRequest request = (ConnectionRequest) msg.obj;
                    if (isPendingConnection(request.getCallId())) {
                        destroyConnection();
                    }
                    break;
                }
                case MSG_SET_ACTIVE:
                    if (isCurrentConnection(msg.obj)) {
                        mConnection.setState(Connection.State.ACTIVE);
                    }
                    break;
                case MSG_SET_RINGING:
                    if (isCurrentConnection(msg.obj)) {
                        mConnection.setState(Connection.State.RINGING);
                    }
                    break;
                case MSG_SET_DIALING:
                    if (isCurrentConnection(msg.obj)) {
                        mConnection.setState(Connection.State.DIALING);
                    }
                    break;
                case MSG_SET_DISCONNECTED: {
                    SomeArgs args = (SomeArgs) msg.obj;
                    try {
                        if (isCurrentConnection(args.arg1)) {
                            mConnection.setDisconnected(args.argi1, (String) args.arg2);
                        }
                    } finally {
                        args.recycle();
                    }
                    break;
                }
                case MSG_SET_ON_HOLD:
                    if (isCurrentConnection(msg.obj)) {
                        mConnection.setState(Connection.State.HOLDING);
                    }
                    break;
                case MSG_SET_REQUESTING_RINGBACK:
                    if (isCurrentConnection(msg.obj)) {
                        mConnection.setRequestingRingback(msg.arg1 == 1);
                    }
                    break;
                case MSG_SET_CALL_CAPABILITIES:
                    if (isCurrentConnection(msg.obj)) {
                        mConnection.setCallCapabilities(msg.arg1);
                    }
                    break;
                case MSG_SET_IS_CONFERENCED:
                    // not supported for remote connections.
                    break;
                case MSG_ADD_CONFERENCE_CALL:
                    // not supported for remote connections.
                    break;
                case MSG_REMOVE_CALL:
                    if (isCurrentConnection(msg.obj)) {
                        destroyConnection();
                    }
                    break;
                case MSG_ON_POST_DIAL_WAIT: {
                    SomeArgs args = (SomeArgs) msg.obj;
                    try {
                        if (isCurrentConnection(args.arg1)) {
                            mConnection.setPostDialWait((String) args.arg2);
                        }
                    } finally {
                        args.recycle();
                    }
                    break;
                }
                case MSG_QUERY_REMOTE_CALL_SERVICES:
                    // Not supported from remote connection service.
                    break;
                case MSG_SET_VIDEO_STATE:
                    if (isCurrentConnection(msg.obj)) {
                        mConnection.setVideoState(msg.arg1);
                    }
                    break;
                case MSG_SET_CALL_VIDEO_PROVIDER:
                    // not supported for remote connections.
                    break;
                case MSG_SET_AUDIO_MODE_IS_VOIP:
                    if (isCurrentConnection(msg.obj)) {
                        mConnection.setAudioModeIsVoip(msg.arg1 == 1);
                    }
                    break;
                case MSG_SET_STATUS_HINTS: {
                    SomeArgs args = (SomeArgs) msg.obj;
                    try {
                        if (isCurrentConnection(args.arg1)) {
                            mConnection.setStatusHints((StatusHints) args.arg2);
                        }
                    } finally {
                        args.recycle();
                    }
                    break;
                }
                case MSG_SET_HANDLE: {
                    SomeArgs args = (SomeArgs) msg.obj;
                    try {
                        if (isCurrentConnection(args.arg1)) {
                            mConnection.setHandle((Uri) args.arg2, args.argi1);
                        }
                    } finally {
                        args.recycle();
                    }
                    break;
                }
                case MSG_SET_CALLER_DISPLAY_NAME: {
                    SomeArgs args = (SomeArgs) msg.obj;
                    try {
                        if (isCurrentConnection(msg.arg1)) {
                            mConnection.setCallerDisplayName((String) args.arg2, args.argi1);
                        }
                    } finally {
                        args.recycle();
                    }
                    break;
                }
                case MSG_START_ACTIVITY_FROM_IN_CALL: {
                    SomeArgs args = (SomeArgs) msg.obj;
                    try {
                        if (isCurrentConnection(msg.arg1)) {
                            mConnection.startActivityFromInCall((PendingIntent) args.arg2);
                        }
                    } finally {
                        args.recycle();
                    }
                    break;
                }
            }
        }


    };

    private final IConnectionServiceAdapter mAdapter = new IConnectionServiceAdapter.Stub() {
        @Override
        public void handleCreateConnectionSuccessful(
                ConnectionRequest request, ParcelableConnection connection) {
            SomeArgs args = SomeArgs.obtain();
            args.arg1 = request;
            args.arg2 = connection;
            mHandler.obtainMessage(MSG_HANDLE_CREATE_CONNECTION_SUCCESSFUL, args).sendToTarget();
        }

        @Override
        public void handleCreateConnectionFailed(
                ConnectionRequest request, int errorCode, String errorMessage) {
            SomeArgs args = SomeArgs.obtain();
            args.arg1 = request;
            args.argi1 = errorCode;
            args.arg2 = errorMessage;
            mHandler.obtainMessage(MSG_HANDLE_CREATE_CONNECTION_FAILED, args).sendToTarget();
        }

        @Override
        public void handleCreateConnectionCancelled(ConnectionRequest request) {
            mHandler.obtainMessage(MSG_HANDLE_CREATE_CONNECTION_CANCELLED, request).sendToTarget();
        }

        @Override
        public void setActive(String connectionId) {
            mHandler.obtainMessage(MSG_SET_ACTIVE, connectionId).sendToTarget();
        }

        @Override
        public void setRinging(String connectionId) {
            mHandler.obtainMessage(MSG_SET_RINGING, connectionId).sendToTarget();
        }

        @Override
        public void setDialing(String connectionId) {
            mHandler.obtainMessage(MSG_SET_DIALING, connectionId).sendToTarget();
        }

        @Override
        public void setDisconnected(
                String connectionId, int disconnectCause, String disconnectMessage) {
            SomeArgs args = SomeArgs.obtain();
            args.arg1 = connectionId;
            args.arg2 = disconnectMessage;
            args.argi1 = disconnectCause;
            mHandler.obtainMessage(MSG_SET_DISCONNECTED, args).sendToTarget();
        }

        @Override
        public void setOnHold(String connectionId) {
            mHandler.obtainMessage(MSG_SET_ON_HOLD, connectionId).sendToTarget();
        }

        @Override
        public void setRequestingRingback(String connectionId, boolean ringback) {
            mHandler.obtainMessage(MSG_SET_REQUESTING_RINGBACK, ringback ? 1 : 0, 0, connectionId)
                    .sendToTarget();
        }

        @Override
        public void setCallCapabilities(String connectionId, int callCapabilities) {
            mHandler.obtainMessage(MSG_SET_CALL_CAPABILITIES, callCapabilities, 0, connectionId)
                    .sendToTarget();
        }

        @Override
        public void setIsConferenced(String connectionId, String conferenceConnectionId) {
            // not supported for remote connections.
        }

        @Override
        public void addConferenceCall(String connectionId) {
            // not supported for remote connections.
        }

        @Override
        public void removeCall(String connectionId) {
            mHandler.obtainMessage(MSG_REMOVE_CALL, connectionId).sendToTarget();
        }

        @Override
        public void onPostDialWait(String connectionId, String remainingDigits) {
            SomeArgs args = SomeArgs.obtain();
            args.arg1 = connectionId;
            args.arg2 = remainingDigits;
            mHandler.obtainMessage(MSG_ON_POST_DIAL_WAIT, args).sendToTarget();
        }

        @Override
        public void queryRemoteConnectionServices(RemoteServiceCallback callback) {
            try {
                // Not supported from remote connection service.
                callback.onError();
            } catch (RemoteException e) {
            }
        }

        @Override
        public void setVideoState(String connectionId, int videoState) {
            mHandler.obtainMessage(MSG_SET_VIDEO_STATE, videoState, 0, connectionId).sendToTarget();
        }

        @Override
        public void setVideoCallProvider(
                String connectionId, IVideoCallProvider videoCallProvider) {
            // not supported for remote connections.
        }

        @Override
        public final void setAudioModeIsVoip(String connectionId, boolean isVoip) {
            mHandler.obtainMessage(MSG_SET_AUDIO_MODE_IS_VOIP, isVoip ? 1 : 0, 0,
                    connectionId).sendToTarget();
        }

        @Override
        public final void setStatusHints(String connectionId, StatusHints statusHints) {
            SomeArgs args = SomeArgs.obtain();
            args.arg1 = connectionId;
            args.arg2 = statusHints;
            mHandler.obtainMessage(MSG_SET_STATUS_HINTS, args).sendToTarget();
        }

        @Override
        public final void setHandle(String connectionId, Uri handle, int presentation) {
            SomeArgs args = SomeArgs.obtain();
            args.arg1 = connectionId;
            args.arg2 = handle;
            args.argi1 = presentation;
            mHandler.obtainMessage(MSG_SET_HANDLE, args).sendToTarget();
        }

        @Override
        public final void setCallerDisplayName(
                String connectionId, String callerDisplayName, int presentation) {
            SomeArgs args = SomeArgs.obtain();
            args.arg1 = connectionId;
            args.arg2 = callerDisplayName;
            args.argi1 = presentation;
            mHandler.obtainMessage(MSG_SET_CALLER_DISPLAY_NAME, args).sendToTarget();
        }

        @Override
        public final void startActivityFromInCall(String connectionId, PendingIntent intent) {
            SomeArgs args = SomeArgs.obtain();
            args.arg1 = connectionId;
            args.arg2 = intent;
            mHandler.obtainMessage(MSG_START_ACTIVITY_FROM_IN_CALL, args).sendToTarget();
        }
    };

    RemoteConnectionService(ComponentName componentName, IConnectionService connectionService)
            throws RemoteException {
        mComponentName = componentName;
        mConnectionService = connectionService;

        mConnectionService.addConnectionServiceAdapter(mAdapter);
        mConnectionService.asBinder().linkToDeath(this, 0);
    }

    @Override
    public String toString() {
        return "[RemoteCS - " + mConnectionService.asBinder().toString() + "]";
    }

    /** ${inheritDoc} */
    @Override
    public final void binderDied() {
        if (mConnection != null) {
            destroyConnection();
        }

        release();
    }

    final RemoteConnection createRemoteConnection(ConnectionRequest request, boolean isIncoming) {
        if (mConnectionId == null) {
            String id = UUID.randomUUID().toString();
            ConnectionRequest newRequest = new ConnectionRequest(
                    request.getAccountHandle(),
                    id,
                    request.getHandle(),
                    request.getHandlePresentation(),
                    request.getExtras(),
                    request.getVideoState());
            mConnection = new RemoteConnection(mConnectionService, request, isIncoming);
            try {
                mConnectionService.createConnection(newRequest, isIncoming);
                mConnectionId = id;
            } catch (RemoteException e) {
                mConnection = RemoteConnection.failure(DisconnectCause.ERROR_UNSPECIFIED,
                        e.toString());
            }
            return mConnection;
        } else {
            return RemoteConnection.failure(DisconnectCause.ERROR_UNSPECIFIED, null);
        }
    }

    /**
     * Releases the resources associated with this Remote connection service. Should be called when
     * the remote service is no longer being used.
     */
    void release() {
        mConnectionService.asBinder().unlinkToDeath(this, 0);
    }

    private boolean isPendingConnection(String id) {
        return TextUtils.equals(mConnectionId, id);
    }

    private boolean isCurrentConnection(Object obj) {
        return obj instanceof String && mConnection != null &&
                TextUtils.equals(mConnectionId, (String) obj);
    }

    private void destroyConnection() {
        mConnection.setDestroyed();
        mConnection = null;
        mConnectionId = null;
    }
}
