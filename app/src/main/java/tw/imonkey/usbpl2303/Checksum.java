

package tw.imonkey.usbpl2303;
public class Checksum {
    public static byte LRC(byte[] bytes) {
        byte iLRC = 0x00;
        for (byte aByte : bytes) {
            iLRC ^= aByte;
        }
        return iLRC;
    }

    public static byte SUM(byte[] bytes){
        byte iSUM  = 0x00;
        for (byte aByte : bytes) {
            iSUM += aByte;
        }
        return iSUM;
    }

    public static byte CRC(byte[] bytes){
        byte iCRC;
        iCRC=0x00;
        return iCRC;
    }
}

