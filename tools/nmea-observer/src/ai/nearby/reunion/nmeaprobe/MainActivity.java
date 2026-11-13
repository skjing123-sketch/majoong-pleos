package ai.nearby.reunion.nmeaprobe;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.ArrayList;

/** Start only while parked and visible. This Activity does not control navigation. */
public final class MainActivity extends Activity {
    private static final int REQUEST = 20;
    private TextView status;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (24 * getResources().getDisplayMetrics().density);
        root.setPadding(pad, pad, pad, pad);
        status = new TextView(this);
        status.setTextSize(22);
        status.setText("마중 NMEA 진단\n\n정차 상태에서 시작하세요. GPS 문장 종류·길이·체크섬 유효 여부와 사용 위성 수만 기록합니다. 좌표 원문·연락처는 기록하지 않습니다.\n\n시작 뒤 기본 내비를 열어 비교할 수 있습니다. 5분 후 자동으로 중지됩니다. 실제 수신 여부는 아직 검증되지 않았습니다.");
        root.addView(status);
        Button start = new Button(this);
        start.setText("진단 시작 · 최대 5분");
        start.setOnClickListener(view -> requestOrStart());
        root.addView(start);
        Button stop = new Button(this);
        stop.setText("진단 중지");
        stop.setOnClickListener(view -> {
            stopService(new Intent(this, NmeaProbeService.class));
            status.setText("진단 서비스를 중지했습니다. 내비와 마중 앱에는 종료 요청을 보내지 않았습니다.");
        });
        root.addView(stop);
        setContentView(root);
    }

    private boolean permitted() {
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
            && (Build.VERSION.SDK_INT < 33 || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED);
    }

    private void requestOrStart() {
        if (!permitted()) {
            ArrayList<String> permissions = new ArrayList<>();
            permissions.add(Manifest.permission.ACCESS_FINE_LOCATION);
            permissions.add(Manifest.permission.ACCESS_COARSE_LOCATION);
            if (Build.VERSION.SDK_INT >= 33) permissions.add(Manifest.permission.POST_NOTIFICATIONS);
            requestPermissions(permissions.toArray(new String[0]), REQUEST);
            return;
        }
        begin();
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode != REQUEST) return;
        if (permitted()) begin();
        else status.setText("정확한 위치와 알림 권한이 필요합니다. 승인하지 않은 상태에서는 진단을 시작하지 않습니다.");
    }

    private void begin() {
        try {
            startForegroundService(new Intent(this, NmeaProbeService.class).setAction(NmeaProbeService.ACTION_START));
            status.setText("진단 시작을 요청했습니다. 알림과 logcat의 MajoongNmeaProbe 태그로 실제 실행·수신을 확인하세요. 최대 5분 후 중지됩니다.\n\n이 요청 자체는 NMEA 수신이나 내비의 파싱 성공 증거가 아닙니다.");
        } catch (RuntimeException ignored) {
            status.setText("진단 서비스를 시작하지 못했습니다. 정차·권한·앱 전면 상태를 확인하세요.");
        }
    }
}
