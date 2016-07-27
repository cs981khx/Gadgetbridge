package nodomain.freeyourgadget.gadgetbridge.service.devices.miband.operations;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;
import android.content.Intent;
import android.database.sqlite.SQLiteDatabase;
import android.preference.PreferenceActivity;
import android.util.Log;
import android.widget.Toast;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.Timer;
import java.util.TimerTask;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import cz.msebera.android.httpclient.Header;
import cz.msebera.android.httpclient.entity.StringEntity;
import nodomain.freeyourgadget.gadgetbridge.GBApplication;
import nodomain.freeyourgadget.gadgetbridge.R;
import nodomain.freeyourgadget.gadgetbridge.database.DBHandler;
import nodomain.freeyourgadget.gadgetbridge.devices.DeviceCoordinator;
import nodomain.freeyourgadget.gadgetbridge.devices.RESTClient;
import nodomain.freeyourgadget.gadgetbridge.devices.SampleProvider;
import nodomain.freeyourgadget.gadgetbridge.devices.miband.GBBandActivity;
import nodomain.freeyourgadget.gadgetbridge.devices.miband.MiBandConst;
import nodomain.freeyourgadget.gadgetbridge.devices.miband.MiBandDateConverter;
import nodomain.freeyourgadget.gadgetbridge.devices.miband.MiBandSampleProvider;
import nodomain.freeyourgadget.gadgetbridge.devices.miband.MiBandService;
import nodomain.freeyourgadget.gadgetbridge.impl.GBActivitySample;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.model.ActivitySample;
import nodomain.freeyourgadget.gadgetbridge.service.btle.TransactionBuilder;
import nodomain.freeyourgadget.gadgetbridge.service.btle.actions.SetDeviceBusyAction;
import nodomain.freeyourgadget.gadgetbridge.service.devices.miband.MiBandSupport;
import nodomain.freeyourgadget.gadgetbridge.util.DateTimeUtils;
import nodomain.freeyourgadget.gadgetbridge.util.GB;
import nodomain.freeyourgadget.gadgetbridge.util.Prefs;

//import java.util.concurrent.Executors;
//import java.util.concurrent.ScheduledExecutorService;
//import java.util.concurrent.ScheduledFuture;
import org.json.*;

import com.google.gson.Gson;
import com.loopj.android.http.*;

import static android.content.Intent.getIntent;

/**
 * An operation that fetches activity data. For every fetch, a new operation must
 * be created, i.e. an operation may not be reused for multiple fetches.
 */
public class FetchActivityOperation extends AbstractMiBandOperation {
    private static final Logger LOG = LoggerFactory.getLogger(FetchActivityOperation.class);
    private static final byte[] fetch = new byte[]{MiBandService.COMMAND_FETCH_DATA};

    //sometimes the Mi Band stops sending data, we confirm the part of the data transfer after some time
    private ScheduledExecutorService scheduleTaskExecutor;
    private ScheduledFuture scheduledTask;

    private final int activityMetadataLength = 11;

    //temporary buffer, size is a multiple of 60 because we want to store complete minutes (1 minute = 3 or 4 bytes)
    private final int activityDataHolderSize;
    private final boolean hasExtendedActivityData;

    private static class ActivityStruct {
        private final byte[] activityDataHolder;
        private final int activityDataHolderSize;
        //index of the buffer above
        private int activityDataHolderProgress = 0;
        //number of bytes we will get in a single data transfer, used as counter
        private int activityDataRemainingBytes = 0;
        //same as above, but remains untouched for the ack message
        private int activityDataUntilNextHeader = 0;
        //timestamp of the single data transfer, incremented to store each minute's data
        private GregorianCalendar activityDataTimestampProgress = null;
        //same as above, but remains untouched for the ack message
        private GregorianCalendar activityDataTimestampToAck = null;

        ActivityStruct(int activityDataHolderSize) {
            this.activityDataHolderSize = activityDataHolderSize;
            activityDataHolder = new byte[activityDataHolderSize];
        }

        public boolean hasRoomFor(byte[] value) {
            return activityDataRemainingBytes >= value.length;
        }

        public boolean isValidData(byte[] value) {
            //I don't like this clause, but until we figure out why we get different data sometimes this should work
            return value.length == 20 || value.length == activityDataRemainingBytes;
        }

        public boolean isBufferFull() {
            return activityDataHolderSize == activityDataHolderProgress;
        }

        public void buffer(byte[] value) {
            System.arraycopy(value, 0, activityDataHolder, activityDataHolderProgress, value.length);
            activityDataHolderProgress += value.length;
            activityDataRemainingBytes -= value.length;

            validate();
        }

        private void validate() {
            GB.assertThat(activityDataRemainingBytes >= 0, "Illegal state, remaining bytes is negative");
        }

        public boolean isFirstChunk() {
            return activityDataTimestampProgress == null;
        }

        public void startNewBlock(GregorianCalendar timestamp, int dataUntilNextHeader) {
            GB.assertThat(timestamp != null, "Timestamp must not be null");

            if (isFirstChunk()) {
                activityDataTimestampProgress = timestamp;
            } else {
                if (timestamp.getTimeInMillis() >= activityDataTimestampProgress.getTimeInMillis()) {
                    activityDataTimestampProgress = timestamp;
                } else {
                    // something is fishy here... better not trust the given timestamp and simply
                    // (re)use the current one
                    // we do accept the timestamp to ack though, so that the bogus data is properly cleared on the band
                    LOG.warn("Got bogus timestamp: " + timestamp.getTime() + " that is smaller than the previous timestamp: " + activityDataTimestampProgress.getTime());
                }
            }
            activityDataTimestampToAck = (GregorianCalendar) timestamp.clone();
            activityDataRemainingBytes = activityDataUntilNextHeader = dataUntilNextHeader;
            validate();
        }

        public boolean isBlockFinished() {
            return activityDataRemainingBytes == 0;
        }

        public void bufferFlushed(int minutes) {
            activityDataTimestampProgress.add(Calendar.MINUTE, minutes);
            activityDataHolderProgress = 0;
        }
    }

    private ActivityStruct activityStruct;

    public FetchActivityOperation(MiBandSupport support) {
        super(support);
        hasExtendedActivityData = support.getDeviceInfo().supportsHeartrate();
        activityDataHolderSize = getBytesPerMinuteOfActivityData() * 60 * 4; // 4h
        activityStruct = new ActivityStruct(activityDataHolderSize);
    }

    @Override
    protected void doPerform() throws IOException {
//        scheduleTaskExecutor = Executors.newScheduledThreadPool(1);

        TransactionBuilder builder = performInitialized("fetch activity data");
        getSupport().setLowLatency(builder);
        builder.add(new SetDeviceBusyAction(getDevice(), getContext().getString(R.string.busy_task_fetch_activity_data), getContext()));
        builder.write(getCharacteristic(MiBandService.UUID_CHARACTERISTIC_CONTROL_POINT), fetch);
        builder.queue(getQueue());
    }

    @Override
    public void onCharacteristicChanged(BluetoothGatt gatt,
                                        BluetoothGattCharacteristic characteristic) {
        UUID characteristicUUID = characteristic.getUuid();
        if (MiBandService.UUID_CHARACTERISTIC_ACTIVITY_DATA.equals(characteristicUUID)) {
            handleActivityNotif(characteristic.getValue());
        } else {
            super.onCharacteristicChanged(gatt, characteristic);
        }
    }

    private void handleActivityFetchFinish() throws IOException {
        LOG.info("Fetching activity data has finished.");
        activityStruct = null;
        operationFinished();
        unsetBusy();
    }

    /**
     * Method to handle the incoming activity data.
     * There are two kind of messages we currently know:
     * - the first one is 11 bytes long and contains metadata (how many bytes to expect, when the data starts, etc.)
     * - the second one is 20 bytes long and contains the actual activity data
     * <p/>
     * The first message type is parsed by this method, for every other length of the value param, bufferActivityData is called.
     *
     * @param value
     * @see #bufferActivityData(byte[])
     */
    private void handleActivityNotif(byte[] value) {
        if (!isOperationRunning()) {
            LOG.error("ignoring activity data notification because operation is not running. Data length: " + value.length);
            getSupport().logMessageContent(value);
            return;
        }

        if (value.length == activityMetadataLength) {
            handleActivityMetadata(value);
        } else {
            bufferActivityData(value);
        }
        LOG.debug("activity data: length: " + value.length + ", remaining bytes: " + activityStruct.activityDataRemainingBytes);

        GB.updateTransferNotification(getContext().getString(R.string.busy_task_fetch_activity_data), true, (int) (((float) (activityStruct.activityDataUntilNextHeader - activityStruct.activityDataRemainingBytes)) / activityStruct.activityDataUntilNextHeader * 100), getContext());

        if (activityStruct.isBlockFinished()) {
            sendAckDataTransfer(activityStruct.activityDataTimestampToAck, activityStruct.activityDataUntilNextHeader);
            GB.updateTransferNotification("", false, 100, getContext());
        }
    }

    private void handleActivityMetadata(byte[] value) {

        if (value.length != activityMetadataLength) {
            return;
        }

        // byte 0 is the data type: 1 means that each minute is represented by a triplet of bytes
        int dataType = value[0];
        // byte 1 to 6 represent a timestamp
        GregorianCalendar timestamp = MiBandDateConverter.rawBytesToCalendar(value, 1);

        // counter of all data held by the band
        int totalDataToRead = (value[7] & 0xff) | ((value[8] & 0xff) << 8);
        totalDataToRead *= (dataType == MiBandService.MODE_REGULAR_DATA_LEN_MINUTE) ? getBytesPerMinuteOfActivityData() : 1;


        // counter of this data block
        int dataUntilNextHeader = (value[9] & 0xff) | ((value[10] & 0xff) << 8);
        dataUntilNextHeader *= (dataType == MiBandService.MODE_REGULAR_DATA_LEN_MINUTE) ? getBytesPerMinuteOfActivityData() : 1;

        // there is a total of totalDataToRead that will come in chunks (3 or 4 bytes per minute if dataType == 1 (MiBandService.MODE_REGULAR_DATA_LEN_MINUTE)),
        // these chunks are usually 20 bytes long and grouped in blocks
        // after dataUntilNextHeader bytes we will get a new packet of 11 bytes that should be parsed
        // as we just did

        if (activityStruct.isFirstChunk() && dataUntilNextHeader != 0) {
            GB.toast(getContext().getString(R.string.user_feedback_miband_activity_data_transfer,
                    DateTimeUtils.formatDurationHoursMinutes((totalDataToRead / getBytesPerMinuteOfActivityData()), TimeUnit.MINUTES),
                    DateFormat.getDateTimeInstance().format(timestamp.getTime())), Toast.LENGTH_LONG, GB.INFO);
        }
        LOG.info("total data to read: " + totalDataToRead + " len: " + (totalDataToRead / getBytesPerMinuteOfActivityData()) + " minute(s)");
        LOG.info("data to read until next header: " + dataUntilNextHeader + " len: " + (dataUntilNextHeader / getBytesPerMinuteOfActivityData()) + " minute(s)");
        LOG.info("TIMESTAMP: " + DateFormat.getDateTimeInstance().format(timestamp.getTime()) + " magic byte: " + dataUntilNextHeader);

        activityStruct.startNewBlock(timestamp, dataUntilNextHeader);
    }

    private int getBytesPerMinuteOfActivityData() {
        return hasExtendedActivityData ? 4 : 3;
    }

    /**
     * Method to store temporarily the activity data values got from the Mi Band.
     * <p/>
     * Since we expect chunks of 20 bytes each, we do not store the received bytes it the length is different.
     *
     * @param  value
     */
    private void bufferActivityData(byte[] value) {

        if (scheduledTask != null) {
            scheduledTask.cancel(true);
        }

        if (activityStruct.hasRoomFor(value)) {
            if (activityStruct.isValidData(value)) {
                activityStruct.buffer(value);
                ScheduledExecutorService service = Executors.newSingleThreadScheduledExecutor();

                service.scheduleAtFixedRate(new Runnable() {
                    @Override
                    public void run() {
                        GB.toast(getContext(), "chiederei " + activityStruct.activityDataTimestampToAck + "   "+ activityStruct.activityDataUntilNextHeader, Toast.LENGTH_LONG, GB.ERROR);
                        //sendAckDataTransfer(activityStruct.activityDataTimestampToAck, activityStruct.activityDataUntilNextHeader);
                        LOG.debug("runnable called");
                    }
                }, 0, 1000, TimeUnit.MILLISECONDS);



//                Timer timer = new Timer();
//
//                timer.scheduleAtFixedRate(new TimerTask() {
//
//                        synchronized public void run() {
//
//                            sendAckDataTransfer(activityStruct.activityDataTimestampToAck, activityStruct.activityDataUntilNextHeader);
//                        }
//
//                }, TimeUnit.MINUTES.toMillis(1), TimeUnit.MINUTES.toMillis(1));

                if (activityStruct.isBufferFull()) {
                    //flushActivityDataHolder();
                    postActivityDataHolder();
                }
            } else {
                // the length of the chunk is not what we expect. We need to make sense of this data
                LOG.warn("GOT UNEXPECTED ACTIVITY DATA WITH LENGTH: " + value.length + ", EXPECTED LENGTH: " + activityStruct.activityDataRemainingBytes);
                getSupport().logMessageContent(value);
            }
        } else {
            GB.toast(getContext(), "error buffering activity data: remaining bytes: " + activityStruct.activityDataRemainingBytes + ", received: " + value.length, Toast.LENGTH_LONG, GB.ERROR);
            try {
                TransactionBuilder builder = performInitialized("send stop sync data");
                builder.write(getCharacteristic(MiBandService.UUID_CHARACTERISTIC_CONTROL_POINT), new byte[]{MiBandService.COMMAND_STOP_SYNC_DATA});
                builder.queue(getQueue());
                GB.updateTransferNotification("Data transfer failed", false, 0, getContext());
                handleActivityFetchFinish();

            } catch (IOException e) {
                LOG.error("error stopping activity sync", e);
            }
        }
    }

    public void getPublicTimeline() throws JSONException {
        RESTClient.get("statuses/public_timeline.json", null, new JsonHttpResponseHandler() {

            public void onSuccess(int statusCode, PreferenceActivity.Header[] headers, JSONObject response) {
                // If the response is JSONObject instead of expected JSONArray
            }


            public void onSuccess(int statusCode, PreferenceActivity.Header[] headers, JSONArray timeline) {
                // Pull out the first event on the public timeline
                //JSONObject firstEvent = timeline.get(0);
                //String tweetText = firstEvent.getString("text");

                // Do something with the response
                //System.out.println();
            }

            public void onFailure(int statusCode, PreferenceActivity.Header[] headers, Throwable throwable, JSONObject errorResponse) {
                LOG.error(errorResponse.toString());
                if (statusCode == 404) {
                    Toast.makeText(getContext(), "404 - Nie odnaleziono serwera!", Toast.LENGTH_LONG).show();
                } else if (statusCode == 500) {
                    Toast.makeText(getContext(), "500 - Coś poszło nie tak po stronie serwera!", Toast.LENGTH_LONG).show();
                } else if (statusCode == 403) {
                    Toast.makeText(getContext(), "Podano niepoprawne dane!", Toast.LENGTH_LONG).show();
                } else {
                    Toast.makeText(getContext(), throwable.toString(), Toast.LENGTH_LONG).show();
                }
            }
        });
    }

    private void invokeWS(String jsonObject) throws UnsupportedEncodingException {
        StringEntity entity = new StringEntity(jsonObject);
        SyncHttpClient client = new SyncHttpClient();
        client.post(getContext(), "http://care.phildavies.com.au/TakeCare/api/values/post", entity, "application/json", new JsonHttpResponseHandler() {
            @Override
            public void onSuccess(int statusCode, Header[] headers, JSONObject obj) {
                try {
           //         Log.i("SER", "HERE!");
             //       String login = obj.getString("login");
                    int ID = obj.getInt("id");
                    //user.setUserId(obj.getInt("userid"));
                } catch (JSONException e) {
                    // TODO Auto-generated catch block
         //           Toast.makeText(getContext(), "Error Occured [Server's JSON response might be invalid]!", Toast.LENGTH_LONG).show();
                    e.printStackTrace();
                }
            }

            @Override
            public void onFailure(int statusCode, Header[] headers, Throwable throwable, JSONObject errorResponse) {
                LOG.error(errorResponse.toString());
                if (statusCode == 404) {
                   // Toast.makeText(getContext(), "404 - Nie odnaleziono serwera!", Toast.LENGTH_LONG).show();
                } else if (statusCode == 500) {
                    //Toast.makeText(getContext(), "500 - Coś poszło nie tak po stronie serwera!", Toast.LENGTH_LONG).show();
                } else if (statusCode == 403) {
                    //Toast.makeText(getContext(), "Podano niepoprawne dane!", Toast.LENGTH_LONG).show();
                } else {
                    //Toast.makeText(getContext(), throwable.toString(), Toast.LENGTH_LONG).show();
                }
            }
        });
    }

    private String getDate(String timeStampStr){

        try{
            DateFormat sdf = new SimpleDateFormat("MM/dd/yyyy");
            Date netDate = (new Date(Long.parseLong(timeStampStr)));
            return sdf.format(netDate);
        }
        catch(Exception ex){
            return ex.getMessage();
        }
    }

    /**
     * empty the local buffer for activity data, arrange the values received in groups of three and
     * store them in the DB
     */
    private void flushActivityDataHolder() {
        if (activityStruct == null) {
            LOG.debug("nothing to flush, struct is already null");
            return;
        }
        int bpm = getBytesPerMinuteOfActivityData();
        LOG.debug("flushing activity data samples: " + activityStruct.activityDataHolderProgress / bpm);
        byte category, intensity, steps, heartrate = 0;

        DBHandler dbHandler = null;
        try {
            dbHandler = GBApplication.acquireDB();
            int minutes = 0;
            try (SQLiteDatabase db = dbHandler.getWritableDatabase()) { // explicitly keep the db open while looping over the samples
                int timestampInSeconds = (int) (activityStruct.activityDataTimestampProgress.getTimeInMillis() / 1000);
                if ((activityStruct.activityDataHolderProgress % bpm) != 0) {
                    throw new IllegalStateException("Unexpected data, progress should be mutiple of " + bpm + ": " + activityStruct.activityDataHolderProgress);
                }
                int numSamples = activityStruct.activityDataHolderProgress / bpm;
                ActivitySample[] samples = new ActivitySample[numSamples];
                SampleProvider sampleProvider = new MiBandSampleProvider();

                for (int i = 0; i < activityStruct.activityDataHolderProgress; i += bpm) {
                    category = activityStruct.activityDataHolder[i];
                    intensity = activityStruct.activityDataHolder[i + 1];
                    steps = activityStruct.activityDataHolder[i + 2];
                    if (hasExtendedActivityData) {
                        heartrate = activityStruct.activityDataHolder[i + 3];
                        LOG.debug("heartrate received: " + (heartrate & 0xff));
                    }

                    samples[minutes] = new GBActivitySample(
                            sampleProvider,
                            timestampInSeconds,
                            intensity & 0xff,
                            steps & 0xff,
                            category & 0xff,
                            heartrate & 0xff);

                    // next minute
                    minutes++;
                    timestampInSeconds += 60;
                }
                dbHandler.addGBActivitySamples(samples);
            } finally {
                activityStruct.bufferFlushed(minutes);
            }
        } catch (Exception ex) {
            GB.toast(getContext(), ex.getMessage(), Toast.LENGTH_LONG, GB.ERROR, ex);
        } finally {
            if (dbHandler != null) {
                dbHandler.release();
            }
        }
    }

    private void postActivityDataHolder() {
        if (activityStruct == null) {
            LOG.debug("nothing to flush, struct is already null");
            return;
        }
        int bpm = getBytesPerMinuteOfActivityData();
        LOG.debug("flushing activity data samples: " + activityStruct.activityDataHolderProgress / bpm);
        byte category, intensity, steps, heartrate = 0;

        DBHandler dbHandler = null;
        try {
            dbHandler = GBApplication.acquireDB();
            int minutes = 0;
            try (SQLiteDatabase db = dbHandler.getWritableDatabase()) { // explicitly keep the db open while looping over the samples
                int timestampInSeconds = (int) (activityStruct.activityDataTimestampProgress.getTimeInMillis() / 1000);
                Date var = activityStruct.activityDataTimestampProgress.getTime();
                String timestamp = new SimpleDateFormat("yyyy-MM-dd hh:mm:ss").format(var);

                if ((activityStruct.activityDataHolderProgress % bpm) != 0) {
                    throw new IllegalStateException("Unexpected data, progress should be mutiple of " + bpm + ": " + activityStruct.activityDataHolderProgress);
                }
                int numSamples = activityStruct.activityDataHolderProgress / bpm;
                GBBandActivity[] samples = new GBBandActivity[numSamples];
                SampleProvider sampleProvider = new MiBandSampleProvider();

                Calendar cal = Calendar.getInstance();
                cal.setTime(var);
                for (int i = 0; i < activityStruct.activityDataHolderProgress; i += bpm) {
                    category = activityStruct.activityDataHolder[i];
                    intensity = activityStruct.activityDataHolder[i + 1];
                    steps = activityStruct.activityDataHolder[i + 2];
                    if (hasExtendedActivityData) {
                        heartrate = activityStruct.activityDataHolder[i + 3];
                        LOG.debug("heartrate received: " + (heartrate & 0xff));
                    }
                    GBDevice gBDevice = getDevice();
                    String macAddress = gBDevice.getAddress();

                    samples[minutes] = new GBBandActivity(
                            macAddress,
                            timestamp,
                            intensity & 0xff,
                            steps & 0xff,
                            category & 0xff,
                            heartrate & 0xff);
                    // next minute
                    minutes++;

                    cal.add(Calendar.MINUTE, 1);
                    Date result = cal.getTime();
                    timestamp = new SimpleDateFormat("yyyy-MM-dd hh:mm:ss").format(result);
                }

                Gson gson = new Gson();
                String jsonInString = gson.toJson(samples);

               // JSONObject jsonObj = new JSONObject(jsonInString);
                invokeWS(jsonInString);
               // dbHandler.addGBActivitySamples(samples);
            } finally {
                activityStruct.bufferFlushed(minutes);
            }
        } catch (Exception ex) {
            //GB.toast(getContext(), ex.getMessage(), Toast.LENGTH_LONG, GB.ERROR, ex);
        } finally {
            if (dbHandler != null) {
                dbHandler.release();
            }
        }
    }

    /**
     * Acknowledge the transfer of activity data to the Mi Band.
     * <p/>
     * After receiving data from the band, it has to be acknowledged. This way the Mi Band will delete
     * the data it has on record.
     *
     * @param time
     * @param bytesTransferred
     */
    private void sendAckDataTransfer(Calendar time, int bytesTransferred) {
        byte[] ackTime = MiBandDateConverter.calendarToRawBytes(time);
        Prefs prefs = GBApplication.getPrefs();

        byte[] ackChecksum = new byte[]{
                (byte) (bytesTransferred & 0xff),
                (byte) (0xff & (bytesTransferred >> 8))
        };
        if (prefs.getBoolean(MiBandConst.PREF_MIBAND_DONT_ACK_TRANSFER, false)) {
            ackChecksum = new byte[]{
                    (byte) (~bytesTransferred & 0xff),
                    (byte) (0xff & (~bytesTransferred >> 8))
            };
        }
        byte[] ack = new byte[]{
                MiBandService.COMMAND_CONFIRM_ACTIVITY_DATA_TRANSFER_COMPLETE,
                ackTime[0],
                ackTime[1],
                ackTime[2],
                ackTime[3],
                ackTime[4],
                ackTime[5],
                ackChecksum[0],
                ackChecksum[1]
        };
        try {
            TransactionBuilder builder = performInitialized("send acknowledge");
            builder.write(getCharacteristic(MiBandService.UUID_CHARACTERISTIC_CONTROL_POINT), ack);
            builder.queue(getQueue());

            // flush to the DB after queueing the ACK
            //flushActivityDataHolder();

           // postActivityDataHolder();

            //The last data chunk sent by the miband has always length 0.
            //When we ack this chunk, the transfer is done.
            if (getDevice().isBusy() && bytesTransferred == 0) {
                //if we are not clearing miband's data, we have to stop the sync
                if (prefs.getBoolean(MiBandConst.PREF_MIBAND_DONT_ACK_TRANSFER, false)) {
                    builder = performInitialized("send acknowledge");
                    builder.write(getCharacteristic(MiBandService.UUID_CHARACTERISTIC_CONTROL_POINT), new byte[]{MiBandService.COMMAND_STOP_SYNC_DATA});
                    getSupport().setHighLatency(builder);
                    builder.queue(getQueue());
                }
                handleActivityFetchFinish();
            }
        } catch (IOException ex) {
            LOG.error("Unable to send ack to MI", ex);
        }
    }
}
