package nodomain.freeyourgadget.gadgetbridge.devices;

/**
 * Created by imocan on 29/06/2016.
 */
import com.loopj.android.http.*;

public class RESTClient {
    private static final String BASE_URL = "http://care.phildavies.com.au/TakeCare/";

    private static SyncHttpClient client = new SyncHttpClient();

    public static void get(String url, RequestParams params, AsyncHttpResponseHandler responseHandler) {
        client.get(getAbsoluteUrl(url), params, responseHandler);
    }

    public static void post(String url, RequestParams params, AsyncHttpResponseHandler responseHandler) {
        client.post(getAbsoluteUrl(url), params, responseHandler);
    }

    private static String getAbsoluteUrl(String relativeUrl) {
        return BASE_URL + relativeUrl;
    }
}