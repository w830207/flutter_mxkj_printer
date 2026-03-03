package com.example.flutter_thermal_printer;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import io.flutter.embedding.engine.plugins.FlutterPlugin;
import io.flutter.plugin.common.MethodCall;
import io.flutter.plugin.common.MethodChannel;
import io.flutter.plugin.common.MethodChannel.MethodCallHandler;
import io.flutter.plugin.common.MethodChannel.Result;
import io.flutter.plugin.common.EventChannel;

/**
 * FlutterThermalPrinterPlugin
 */
public class FlutterThermalPrinterPlugin implements FlutterPlugin, MethodCallHandler {
    private static final String TAG = "ThermalPrinterPlugin";
    private MethodChannel channel;
    private EventChannel eventChannel;
    private Context context;
    private UsbPrinter usbPrinter;

    private BluetoothSocket bluetoothSocket = null;
    private OutputStream outputStream = null;
    private String mac;
    private boolean state = false;

    @Override
    public void onAttachedToEngine(@NonNull FlutterPluginBinding flutterPluginBinding) {
        channel = new MethodChannel(flutterPluginBinding.getBinaryMessenger(), "flutter_thermal_printer");
        eventChannel = new EventChannel(flutterPluginBinding.getBinaryMessenger(), "flutter_thermal_printer/events");
        channel.setMethodCallHandler(this);
        context = flutterPluginBinding.getApplicationContext();
        usbPrinter = new UsbPrinter(context);
        eventChannel.setStreamHandler(usbPrinter);
    }

    @Override
    public void onMethodCall(@NonNull MethodCall call, @NonNull Result result) {
        switch (call.method) {
            case "getPlatformVersion":
                result.success("Android " + android.os.Build.VERSION.RELEASE);
                break;
            case "getUsbDevicesList":
                result.success(usbPrinter.getUsbDevicesList());
                break;
            case "connect": {
                String vendorId = call.argument("vendorId");
                String productId = call.argument("productId");
                usbPrinter.connect(vendorId, productId);
                result.success(false);
                break;
            }
            case "disconnect": {
                String vendorId = call.argument("vendorId");
                String productId = call.argument("productId");
                result.success(usbPrinter.disconnect(vendorId, productId));
                break;
            }
            case "printText": {
                String vendorId = call.argument("vendorId");
                String productId = call.argument("productId");
                List<Integer> data = call.argument("data");
                usbPrinter.printText(vendorId, productId, data);
                result.success(true);
                break;
            }
            case "isConnected": {
                String vendorId = call.argument("vendorId");
                String productId = call.argument("productId");
                result.success(usbPrinter.isConnected(vendorId, productId));
                break;
            }
            case "getPairedBluetoothList": {
                result.success(dispositivosVinculados());
                break;
            }

            case "connectBluetooth": {
                String macimpresora = call.argument("address");

                if (macimpresora != null && macimpresora.length() > 0) {
                    mac = macimpresora;
                } else {
                    result.success(false);
                    return;
                }

                new Handler(Looper.getMainLooper()).post(new Runnable() {
                    @Override
                    public void run() {
                        if (bluetoothSocket != null && bluetoothSocket.isConnected()) {
                            result.success(true);
                            return;
                        }

                        try {
                            OutputStream stream = connectBluetooth();
                            if (stream != null) {
                                outputStream = stream;
                            }
                            result.success(state);
                        } catch (Exception e) {
                            Log.e(TAG, "connectBluetooth error", e);
                            result.success(false);
                        }
                    }
                });
                break;
            }

            case "disconnectBluetooth":{
                try {
                    if(outputStream != null){
                        outputStream.close();
                        outputStream = null;
                        result.success(true);
                    }else{
                        result.success(true);
                    }
                } catch (IOException e) {
                    Log.e(TAG, "disconnectBluetooth error", e);
                    result.success(false);
                }

                break;
            }

            case "printDataBluetooth":{
                @SuppressWarnings("unchecked")
                List<Integer> lista = (List<Integer>) call.argument("data");

                byte[] prefix = "\n".getBytes();
                byte[] bytes = new byte[prefix.length + lista.size()];

// 先加入換行
                System.arraycopy(prefix, 0, bytes, 0, prefix.length);

// 把 List<Int> 轉成 byte[]
                for (int i = 0; i < lista.size(); i++) {
                    bytes[prefix.length + i] = lista.get(i).byteValue();
                }

                if (outputStream != null) {

                    try {

                        int chunkSize = 16 * 1024; // 16 KB
                        int total = bytes.length;
                        int offset = 0;

                        while (offset < total) {
                            int end = Math.min(offset + chunkSize, total);
                            outputStream.write(bytes, offset, end - offset);
                            outputStream.flush();
                            offset = end;
                        }

                        result.success(true);

                    } catch (Exception e) {

                        result.success(false);
                        outputStream = null;
                        Log.e(TAG, "Error al imprimir: " + e.getMessage(), e);
                    }

                } else {
                    result.success(false);
                }

                break;
            }
            default:
                result.notImplemented();
                break;
        }
    }

    private List<Map<String, Object>> dispositivosVinculados() {
        List<Map<String, Object>> listItems = new ArrayList<Map<String, Object>>();
        BluetoothAdapter bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();

        if (bluetoothAdapter == null) {
            return listItems;
        }

        Set<BluetoothDevice> pairedDevices = bluetoothAdapter.getBondedDevices();
        if (pairedDevices != null) {
            for (BluetoothDevice device : pairedDevices) {
                String deviceName = device.getName();
                String deviceHardwareAddress = device.getAddress();
                HashMap<String, Object> deviceData = new HashMap<String, Object>();
                deviceData.put("name", deviceName);
                deviceData.put("address", deviceHardwareAddress);
                listItems.add(deviceData);
            }
        }

        return listItems;
    }

    private OutputStream connectBluetooth() {
        state = false;
        OutputStream os = null;
        BluetoothAdapter bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();

        if (bluetoothAdapter != null && bluetoothAdapter.isEnabled()) {
            try {
                BluetoothDevice bluetoothDevice = bluetoothAdapter.getRemoteDevice(mac);
                if (bluetoothSocket != null) {
                    try { bluetoothSocket.close(); } catch (Exception ignored) {}
                }
                
                bluetoothSocket = bluetoothDevice.createRfcommSocketToServiceRecord(
                        UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
                );

                bluetoothAdapter.cancelDiscovery();
                bluetoothSocket.connect();

                if (bluetoothSocket.isConnected()) {
                    os = bluetoothSocket.getOutputStream();
                    state = true;
                }
            } catch (Exception e) {
                state = false;
                Log.e(TAG, "connect error: " + e.getMessage());
                try {
                    if (bluetoothSocket != null) {
                        bluetoothSocket.close();
                        bluetoothSocket = null;
                    }
                } catch (Exception ignored) {}
            }
        } else {
            state = false;
            Log.d(TAG, "Bluetooth problem");
        }
        return os;
    }

    @Override
    public void onDetachedFromEngine(@NonNull FlutterPluginBinding binding) {
        channel.setMethodCallHandler(null);
    }
}
