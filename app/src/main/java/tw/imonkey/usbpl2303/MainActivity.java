package tw.imonkey.usbpl2303;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;

import com.felhr.usbserial.UsbSerialDevice;
import com.felhr.usbserial.UsbSerialInterface;
import com.google.android.things.pio.Gpio;
import com.google.firebase.database.ChildEventListener;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ServerValue;
import com.google.firebase.database.ValueEventListener;

import java.io.UnsupportedEncodingException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.Calendar;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;
import java.util.TimeZone;
import de.greenrobot.event.EventBus;

public class MainActivity extends Activity {
    private Gpio RESETGpio;
    String RESET="BCM26";
    //**************USBSerialPort
    private static final String TAG = MainActivity.class.getSimpleName();
    //   private static final int USB_VENDOR_ID = 0x0403;//arduino nano FT232RL
    //   private static final int USB_PRODUCT_ID = 0x6001;
    //   private static final int USB_VENDOR_ID = 0x2341;//arduino uno(BT)
    //   private static final int USB_PRODUCT_ID = 0x0043;
    private static final int USB_VENDOR_ID = 0x067B;//PL2303HXD
    private static final int USB_PRODUCT_ID = 0x2303;

    private UsbManager usbManager;
    private UsbDeviceConnection connection;
    private UsbSerialDevice serialDevice;

    //set usb serialport parameters
    int baudRate = 9600 ;
    int dataBits = UsbSerialInterface.DATA_BITS_8;
    int stopBits = UsbSerialInterface.STOP_BITS_1;
    int parity = UsbSerialInterface.PARITY_NONE;
    int flowControl= UsbSerialInterface.FLOW_CONTROL_OFF;
    String buffer = "";
//*******firebase*************
    String memberEmail,deviceId;
    public static final String devicePrefs = "devicePrefs";
    DatabaseReference mClear, mTX, mRX, mFriend, mRS232Live,presenceRef,lastOnlineRef,connectedRef,connectedRefF;
    public MySocketServer mServer;
    private static final int SERVER_PORT = 9402;
    Map<String, Object> alert = new HashMap<>();

    //*******PLC****************
    //set serialport protocol parameters
    String STX=new String(new char[]{0x02});
    String ETX=new String(new char[]{0x03});
    String ENQ=new String(new char[]{0x05});
    String newLine=new String(new char[]{0x0D,0x0A});
    // 0x02:STX,0x03:ETX,0x05:ENQ,0x0A:'/n',0xOD:CR,0x0A:LF,0x3A:':'
    /*1.PLC & PI 透過 RS232 通訊
        a.讀取 Bit Data[use M Register]
            Send Cmd : 0x5 + "00FFBRAM000010" + 0xA + 0xD
            測試讀取範圍 : M0000 ~ M000F (16點)
        b.讀取 Word Data[use D Register]
            Send Cmd : 0x5 + "00FFWRAD000008" + 0xA + 0xD
            測試讀取範圍 : D0000 ~ M0007 (8點)
        //-------------------------------------------------------------------------------------
        //PLC 回傳  [0x02]00FF...........................[0x03]
        */
    String Msg_Word_Rd_Cmd =  "00FFWRAD000010";//  //讀取Word D0000-D000F Cmd
    String Msg_Bit_Rd_Cmd   =  "00FFBRAM001010"; //  //讀取Bit  M0010-M0025 Cmd
    public int ReadType = 0; //0:讀 D Register  1:讀 M Register
    public int ResponseType = 0; //0:回應 D Register Data   1:回應 M Register Data //2017_0517 !Chingan+
    String Old_M_Rcv_Data = "0000000000000000"; //上一次收到的 M Reg Data  //2017_0517 !Chingan+
    String Old_D_Rcv_Data = ""; //上一次收到的 D Reg Data  //2017_0517 !Chingan+
    String New_M_Rcv_Data = "0000000000000000"; //目前收到的 M Reg Data  //2017_0517 !Chingan+
    String New_D_Rcv_Data = ""; //目前收到的 D Reg Data  //2017_0517 !Chingan+
    //
    private Handler handler,handlerTest;
    Runnable runnable,runnableTest;
    int timer = 1000; //讀取指令,送出時 Delay 時間

    String cmd ;
    Map<String, Object> PCMD = new HashMap<>();
    private UsbSerialInterface.UsbReadCallback callback = new UsbSerialInterface.UsbReadCallback() {
        @Override
        public void onReceivedData(byte[] data) {
            try {
                String dataUtf8 = new String(data, "UTF-8");
                buffer += dataUtf8;
                int index; //結束字元位置 index
                while ((index = buffer.indexOf(ETX)) != -1)
                  {
                  //final String dataStr = buffer.substring(0, index + 1).trim();
                  final String dataStr = buffer.substring(5, index).trim(); //跳過起始字元 [0x2] 00FF 共5 個字元
                  //判斷是何種資料
                  if(dataStr.length() > 16)
                    {
                    ResponseType = 0; //0:回應 D Register Data
                    New_D_Rcv_Data = dataStr; //將新收到的資料轉存 New_D_Rcv_Data
                    Log.e(TAG, "D Reg: " + dataStr);
                    }
                  else
                    {
                    ResponseType = 1; // 1:回應 M Register Data
                    New_M_Rcv_Data = dataStr;  //將新收到的資料轉存 New_M_Rcv_Data
                    Log.e(TAG, "M Reg: " + dataStr);
                    }
                  //
                  buffer = buffer.length() == index ? "" : buffer.substring(index + 1);
                  //------------------------------------------------------------------------------------------------------------------------
                  runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                      onSerialDataReceived(dataStr); //處理 RS232 收到的資料
                      }
                    });
                }
            } catch (UnsupportedEncodingException e) {
                Log.e(TAG, "Error receiving USB data", e);
            }
        }
    };

    private final BroadcastReceiver usbDetachedReceiver = new BroadcastReceiver() {
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();

            if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)) {
                UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                if (device != null && device.getVendorId() == USB_VENDOR_ID && device.getProductId() == USB_PRODUCT_ID) {
                    Log.i(TAG, "USB device detached");
                    stopUsbConnection();
                }
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Taipei"));
  //      PeripheralManagerService service = new PeripheralManagerService();
        SharedPreferences settings = getSharedPreferences(devicePrefs, Context.MODE_PRIVATE);
        memberEmail = settings.getString("memberEmail",null);
        deviceId = settings.getString("deviceId",null);
        if (memberEmail==null) {
            memberEmail="test@po-po.com";
            deviceId="PLC_RS232_test";
            startServer();
        }
//        mClear = FirebaseDatabase.getInstance().getReference("/");
//        mClear.setValue(null);
        mRX = FirebaseDatabase.getInstance().getReference("/LOG/RS232/"+deviceId+"/RX/");
        mTX= FirebaseDatabase.getInstance().getReference("/LOG/RS232/"+deviceId+"/TX/");
        deviceOnline();
        usbManager = getSystemService(UsbManager.class);
            // Detach events are sent as a system-wide broadcast
        IntentFilter filter = new IntentFilter(UsbManager.ACTION_USB_DEVICE_DETACHED);
        registerReceiver(usbDetachedReceiver, filter);
        listenUartTX();
        requestDevice();
        reqDeviceTimerTest();
   //     RESETListener();

    }

    @Override
    protected void onResume() {
        super.onResume();
        startUsbConnection();
        EventBus.getDefault().register(this);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        unregisterReceiver(usbDetachedReceiver);
        stopUsbConnection();
        EventBus.getDefault().unregister(this);
        /*
        if (RESETGpio != null) {
            try {
                RESETGpio.close();
            } catch (IOException e) {
                e.printStackTrace();
            } finally {
                RESETGpio = null;
            }
        }
        */
    }
/*
    private void RESETListener(){
        PeripheralManagerService service = new PeripheralManagerService();
        try {
            RESETGpio = service.openGpio(RESET);
            RESETGpio.setDirection(Gpio.DIRECTION_IN);
            RESETGpio.setEdgeTriggerType(Gpio.EDGE_BOTH);
            RESETGpio.registerGpioCallback(new GpioCallback() {
                @Override
                public boolean onGpioEdge(Gpio gpio) {

                    try {
                        if (RESETGpio.getValue()){
                            startServer();
                        }
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                    return true;
                }
            });
        } catch (IOException e) {
            e.printStackTrace();
        }

    }
    */

    private void startUsbConnection() {
        Map<String, UsbDevice> connectedDevices = usbManager.getDeviceList();
        if (!connectedDevices.isEmpty()) {
            for (UsbDevice device : connectedDevices.values()) {
                if (device.getVendorId() == USB_VENDOR_ID && device.getProductId() == USB_PRODUCT_ID) {
                    Log.i(TAG, "Device found: " + device.getDeviceName());
                    startSerialConnection(device);
                    return;
                }
            }
        }
        Log.w(TAG, "Could not start USB connection - No devices found");
    }

    private void startSerialConnection(UsbDevice device) {
        Log.i(TAG, "Ready to open USB device connection");
        connection = usbManager.openDevice(device);
        int iface = 0;// multiple devices
        serialDevice = UsbSerialDevice.createUsbSerialDevice(device, connection,iface);
        if (serialDevice != null) {
            if (serialDevice.open()) {
                serialDevice.setBaudRate(baudRate);
                serialDevice.setDataBits(dataBits);
                serialDevice.setStopBits(stopBits);
                serialDevice.setParity(parity);
                serialDevice.setFlowControl(flowControl);
                serialDevice.read(callback);
                Log.i(TAG, "Serial connection opened");
            } else {
                Log.w(TAG, "Cannot open serial connection");
            }
        } else {
            Log.w(TAG, "Could not create Usb Serial Device");
        }
    }

    //======================================================================================處理 RS232 收到的資料
    private void onSerialDataReceived(String data) {
        deviceRespond(data);
        Log.i(TAG, "Serial data received: " + data); //Monitor Show Data
    }

    //======================================================================================
    private void stopUsbConnection() {
        try {
            if (serialDevice != null) {
                serialDevice.close();
            }

            if (connection != null) {
                connection.close();
            }
        } finally {
            serialDevice = null;
            connection = null;
        }
    }

    // websocket server
    private void startServer() {
        InetAddress inetAddress = getInetAddress();
        if (inetAddress == null) {
            return;
        }
        mServer = new MySocketServer(new InetSocketAddress(inetAddress.getHostAddress(), SERVER_PORT));
        mServer.start();
    }

    private static InetAddress getInetAddress() {
        try {
            for (Enumeration en = NetworkInterface.getNetworkInterfaces(); en.hasMoreElements();) {
                NetworkInterface networkInterface = (NetworkInterface) en.nextElement();

                for (Enumeration enumIpAddr = networkInterface.getInetAddresses(); enumIpAddr.hasMoreElements();) {
                    InetAddress inetAddress = (InetAddress) enumIpAddr.nextElement();

                    if (!inetAddress.isLoopbackAddress() && inetAddress instanceof Inet4Address) {
                        return inetAddress;
                    }
                }
            }
        } catch (SocketException e) {
            e.printStackTrace();
        }

        return null;
    }

    // eventbus:2.4.0
    @SuppressWarnings("UnusedDeclaration")
    public void onEvent(SocketMessageEvent event) {  //  receive message from eventbus
        String message = event.getMessage();
        String[] mArray = message.split(",");
        if (mArray.length==2) {
            memberEmail = mArray[0];
            deviceId =  mArray[1];
            SharedPreferences.Editor editor = getSharedPreferences(devicePrefs, Context.MODE_PRIVATE).edit();
            editor.putString("memberEmail",memberEmail);
            editor.putString("deviceId",deviceId);
            editor.apply();
            mServer.sendMessage("echo: " + message);
            Intent i;
            i = new Intent(this,MainActivity.class);
            startActivity(i);
        }
    }

    //device online check
    private void deviceOnline(){
        mRS232Live=FirebaseDatabase.getInstance().getReference("/LOG/RS232/"+deviceId+"/connection");//for log activity
        mRS232Live.setValue(true);
        mRS232Live.onDisconnect().setValue(null);

        presenceRef = FirebaseDatabase.getInstance().getReference("/FUI/"+memberEmail.replace(".", "_")+"/"+deviceId+"/connection");//for boss's main activity
        presenceRef.setValue(true);
        presenceRef.onDisconnect().setValue(null);
        lastOnlineRef =FirebaseDatabase.getInstance().getReference("/FUI/"+memberEmail.replace(".", "_")+"/"+deviceId+"/lastOnline");
        lastOnlineRef.onDisconnect().setValue(ServerValue.TIMESTAMP);
        connectedRef = FirebaseDatabase.getInstance().getReference(".info/connected");
        connectedRef.addValueEventListener(new ValueEventListener() {
            @Override
            public void onDataChange(DataSnapshot snapshot) {
                boolean connected = snapshot.getValue(Boolean.class);
                if (connected) {
                    presenceRef.setValue(true);
                    mRS232Live.setValue(true);
                }
            }
            @Override
            public void onCancelled(DatabaseError error) {
            }
        });

        mFriend= FirebaseDatabase.getInstance().getReference("/DEVICE/"+deviceId+"/friend"); //for friend's main activity
        mFriend.addValueEventListener(new ValueEventListener() {
            @Override
            public void onDataChange(DataSnapshot snapshot) {
                for (DataSnapshot childSnapshot : snapshot.getChildren()) {
                    final DatabaseReference presenceRefToFirends= FirebaseDatabase.getInstance().getReference("/FUI/"+childSnapshot.getValue().toString().replace(".", "_")+"/"+deviceId+"/connection");
                    presenceRefToFirends.setValue(true);
                    presenceRefToFirends.onDisconnect().setValue(null);
                    connectedRefF = FirebaseDatabase.getInstance().getReference(".info/connected");
                    connectedRefF.addValueEventListener(new ValueEventListener() {
                        @Override
                        public void onDataChange(DataSnapshot snapshot) {
                            boolean connected = snapshot.getValue(Boolean.class);
                            if (connected) {
                                presenceRefToFirends.setValue(true);
                            }
                        }
                        @Override
                        public void onCancelled(DatabaseError error) {
                        }
                    });
                }
            }
            @Override
            public void onCancelled(DatabaseError databaseError) {}
        });
    }
    private void listenUartTX() {
        mTX.addChildEventListener(new ChildEventListener() {
            @Override
            public void onChildAdded(DataSnapshot dataSnapshot, String s) {

                if (dataSnapshot.child("message").getValue()!= null) {
                    String oneTimeCMD=dataSnapshot.child("message").getValue().toString();
                    serialDevice.write((ENQ+oneTimeCMD+ETX).getBytes());
                    Log.i(TAG, "Serial data send: " + cmd);
                    //   requestPLC();
                }
            }
            @Override
            public void onChildRemoved(DataSnapshot dataSnapshot) {}
            @Override
            public void onChildMoved(DataSnapshot dataSnapshot, String s) {}
            @Override
            public void onChildChanged(DataSnapshot dataSnapshot, String s) {}
            @Override
            public void onCancelled(DatabaseError databaseError) {}
        });
    }

    //======================================================================================處理 PLC 端回應資料
    private void deviceRespond(String data){
        //todo: data parser
        int i_Rrc_to_Firebase = 0; //1:記錄到 Firebase
        //
    /*    if (data.contains("A")) {
            alert(data); // alert client.
            Map<String, Object> RX = new HashMap<>();
            RX.clear();
            RX.put("message", data);
            RX.put("timeStamp", ServerValue.TIMESTAMP);
            mRX.push().setValue(RX);
        }else if(data.contains("L")){
            Map<String, Object> RX = new HashMap<>();
            RX.clear();
            RX.put("message", data);
            RX.put("timeStamp", ServerValue.TIMESTAMP);
            mRX.push().setValue(RX);
        }
        */
        //資料設定及判斷是否更新到 Firebase ----------------------------------------------------------------------------------------------
        if(ResponseType == 0) //0:回應 D Register Data
          {
          //目前收到的資料是 D Register Data
          if(Old_D_Rcv_Data.equals(New_D_Rcv_Data)) {i_Rrc_to_Firebase = 0;} //1:記錄到 Firebase
          else                                        {i_Rrc_to_Firebase = 1;} //1:記錄到 Firebase
          //
          Old_D_Rcv_Data = New_D_Rcv_Data; //更新 D Register Data
          }
        else if(ResponseType == 1) //1:回應 M Register Data
          {
          //目前收到的資料是 M Register Data
          if(Old_M_Rcv_Data.equals(New_M_Rcv_Data)) {i_Rrc_to_Firebase = 0;} //1:記錄到 Firebase
          else                                         {i_Rrc_to_Firebase = 1;} //1:記錄到 Firebase
          //
          Old_M_Rcv_Data = New_M_Rcv_Data; //更新 M Register Data
          }
        //若需更新就執行更新到 Firebase ----------------------------------------------------------------------------------------------
        if(i_Rrc_to_Firebase == 1)
        {
            DatabaseReference  mRequest= FirebaseDatabase.getInstance().getReference("/LOG/RS232/"+deviceId+"/TX/");
            Map<String, Object> CMD = new HashMap<>();
            CMD.clear();
            //CMD.put("message",data);
            CMD.put("message","M:"+New_M_Rcv_Data + ",D:"+New_D_Rcv_Data);
            CMD.put("memberEmail",memberEmail);
            CMD.put("timeStamp", ServerValue.TIMESTAMP);
            mRequest.push().setValue(CMD);
            Log.i(TAG, "紀錄資料到 Firebase " + data);
        }
    }

    private void requestDevice(){
        PCMD.clear();
        DatabaseReference mRequest= FirebaseDatabase.getInstance().getReference("/DEVICE/"+deviceId+"/REQ/");
        mRequest.addValueEventListener(new ValueEventListener() {
            @Override
            public void onDataChange(DataSnapshot snapshot) {
                for (DataSnapshot childSnapshot : snapshot.getChildren()) {
                    if (childSnapshot.child("message").getValue() != null) {
                        PCMD.put(childSnapshot.getKey(),childSnapshot.child("message").getValue());
                        String CMD = snapshot.child("message").getValue().toString();
                        serialDevice.write((ENQ + CMD + newLine).getBytes());
                        Log.i(TAG, "Serial data send: " + cmd);
                    }
                    reqTimer();
                }
            }
            @Override
            public void onCancelled(DatabaseError databaseError) {}
        });
    }
    private void reqTimer(){
        if (handler!=null){
            handler=null;
        }
        handler = new Handler();
        runnable = new Runnable()
        {
            @Override
            public void run()
            {
            //  String cmd="Android Things";
            //    serialDevice.write((STX+cmd+ETX).getBytes());
                Calendar cTime = Calendar.getInstance();
                for(String PCMDKey:PCMD.keySet()) {
                    boolean flag=true ;
                    long start,now;
                    serialDevice.write((ENQ +PCMD.get(PCMDKey).toString()+newLine).getBytes()); // Async-like operation now! :)
                    start=cTime.getTimeInMillis();
                    while(flag){
                        cTime = Calendar.getInstance();
                        now=cTime.getTimeInMillis();
                        if ((now-start)>100){
                            flag=false;
                        }
                    }
                }
              handler.postDelayed(this, timer);
            }
        };
        handler.postDelayed(runnable, timer);
        }

    //-----------------------------------------------------------------送出指令部分
    private void reqDeviceTimerTest(){
        cmd="Android Things Test";
        handlerTest = new Handler();
        runnableTest = new Runnable()
        {
            @Override
            public void run()
            {
    //          serialDevice.write((STX+cmd+ETX).getBytes());
                String Send_Out = "";
                //
                //0:讀 D Register  1:讀 M Register
                if(ReadType == 0) {
                    Send_Out =ENQ + Msg_Word_Rd_Cmd + newLine;
                    Log.i(TAG, "讀 D Reg: " + Send_Out);  //記錄資料 :讀 D Reg
                    ReadType = 1; //下一次讀  M Register
                }
                else {
                    Send_Out = ENQ+ Msg_Bit_Rd_Cmd + newLine;
                    Log.i(TAG, "讀 M Reg: " + Send_Out);  //記錄資料 :讀 M Reg
                    ReadType = 0; //下一次讀 D Register
                }
                //
                serialDevice.write(Send_Out.getBytes()); //由 RS232 送出讀取指令
                handlerTest.postDelayed(this, timer);
            }
        };
        handlerTest.postDelayed(runnableTest, timer);
    }

    private void alert(String message){
   //     NotifyUser.topicsPUSH(deviceId,memberEmail,"智慧機通知",message);
    //    NotifyUser.IIDPUSH(deviceId,memberEmail,"智慧機通知",message);
  //      NotifyUser.emailPUSH(deviceId,memberEmail,message);
   //     NotifyUser.SMSPUSH(deviceId,memberEmail,message);

        DatabaseReference mAlertMaster= FirebaseDatabase.getInstance().getReference("/FUI/"+memberEmail.replace(".", "_")+"/"+deviceId+"/alert");
        alert.clear();
        alert.put("message",message);
        alert.put("timeStamp", ServerValue.TIMESTAMP);
        mAlertMaster.setValue(alert);
        DatabaseReference mFriend= FirebaseDatabase.getInstance().getReference("/DEVICE/"+deviceId+"/friend");
        mFriend.addListenerForSingleValueEvent(new ValueEventListener() {
            @Override
            public void onDataChange(DataSnapshot snapshot) {
                for (DataSnapshot childSnapshot : snapshot.getChildren()) {
                    DatabaseReference mAlertFriend= FirebaseDatabase.getInstance().getReference("/FUI/"+childSnapshot.getValue().toString().replace(".", "_")+"/"+deviceId+"/alert");
                    mAlertFriend.setValue(alert);
                }
            }
            @Override
            public void onCancelled(DatabaseError databaseError) {}
        });
    }
}