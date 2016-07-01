package nodomain.freeyourgadget.gadgetbridge.devices.miband;



/**
 * Created by imocan on 30/06/2016.
 */
public class GBBandActivity  {

    private final String macAddress;
    private final String timestamp;
    private final int intensity;
    private final int steps;
    private final int type;
    private final int customValue;

    public GBBandActivity(String macAddress, String timestamp, int intensity, int steps, int type, int customValue) {
        this.timestamp = timestamp;
        this.macAddress = macAddress;
        this.intensity = intensity;
        this.steps = steps;
        this.customValue = customValue;
        this.type = type;

    }



    public GBBandActivity(String macAdress, String timestamp, int intensity, int steps, int type) {
        this(macAdress, timestamp, intensity, steps, type, 0);
    }
}
