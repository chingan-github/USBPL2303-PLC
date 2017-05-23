package tw.imonkey.usbpl2303;

public class SocketMessageEvent {
    private String mMessage;

    public SocketMessageEvent(String message) {
        mMessage = message;
    }

    public String getMessage() {
        return mMessage;
    }
}