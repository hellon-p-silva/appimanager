package com.hellon.appimanager;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.TextView;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

class FileAdapter extends BaseAdapter {

    private final LayoutInflater inflater;
    private final Context context;
    private List<File> files = new ArrayList<>();
    private boolean showHidden;

    FileAdapter(Context context) {
        this.context = context;
        this.inflater = LayoutInflater.from(context);
    }

    void setFiles(List<File> files, boolean showHidden) {
        this.files = files;
        this.showHidden = showHidden;
        notifyDataSetChanged();
    }

    @Override
    public int getCount() {
        return files.size();
    }

    @Override
    public File getItem(int position) {
        return files.get(position);
    }

    @Override
    public long getItemId(int position) {
        return position;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        ViewHolder holder;
        if (convertView == null) {
            convertView = inflater.inflate(R.layout.item_file, parent, false);
            holder = new ViewHolder();
            holder.icon = convertView.findViewById(R.id.icon);
            holder.name = convertView.findViewById(R.id.name);
            holder.details = convertView.findViewById(R.id.details);
            convertView.setTag(holder);
        } else {
            holder = (ViewHolder) convertView.getTag();
        }

        File file = getItem(position);
        holder.icon.setImageResource(FileUtils.iconFor(file));
        holder.name.setText(file.getName());
        holder.name.setAlpha(file.isHidden() ? 0.6f : 1f);

        String date = FileUtils.formatDate(file.lastModified());
        if (file.isDirectory()) {
            int count = FileUtils.countChildren(file, showHidden);
            String items = context.getResources().getQuantityString(R.plurals.items, count, count);
            holder.details.setText(items + "  •  " + date);
        } else {
            holder.details.setText(FileUtils.formatSize(file.length()) + "  •  " + date);
        }
        return convertView;
    }

    private static class ViewHolder {
        ImageView icon;
        TextView name;
        TextView details;
    }
}
