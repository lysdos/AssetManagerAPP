package com.huaxia.inventory.ui.result;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import com.huaxia.inventory.R;
import com.huaxia.inventory.data.entity.ScanRecord;
import com.huaxia.inventory.util.DateUtil;
import java.util.List;

public class ExtraResultAdapter extends RecyclerView.Adapter<ExtraResultAdapter.VH> {

    private final List<ScanRecord> items;

    public ExtraResultAdapter(List<ScanRecord> items) { this.items = items; }

    @NonNull @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new VH(LayoutInflater.from(parent.getContext())
                .inflate(R.layout.view_extra_row, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        ScanRecord r = items.get(position);
        h.epc.setText(r.epc == null ? "" : r.epc);
        h.time.setText(DateUtil.formatTimestamp(r.scannedAt));
        h.rssi.setText(r.rssi == null ? "" : r.rssi + " dBm");
    }

    @Override public int getItemCount() { return items.size(); }

    static class VH extends RecyclerView.ViewHolder {
        final TextView epc, time, rssi;
        VH(View v) {
            super(v);
            epc = v.findViewById(R.id.extraEpcTv);
            time = v.findViewById(R.id.extraTimeTv);
            rssi = v.findViewById(R.id.extraRssiTv);
        }
    }
}
