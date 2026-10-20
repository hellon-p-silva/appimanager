package com.hellon.appimanager;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.StatFs;
import android.provider.Settings;
import android.text.InputType;
import android.util.SparseBooleanArray;
import android.util.TypedValue;
import android.view.ActionMode;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.AbsListView;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.SearchView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {

    private static final int REQUEST_PERMISSION = 1;
    private static final String PREFS = "settings";
    private static final String KEY_SORT = "sort";
    private static final String KEY_SORT_DESC = "sort_desc";
    private static final String KEY_HIDDEN = "show_hidden";
    private static final String STATE_DIR = "current_dir";

    private static final int SORT_NAME = 0;
    private static final int SORT_DATE = 1;
    private static final int SORT_SIZE = 2;
    private static final int SORT_TYPE = 3;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Map<String, Integer> scrollPositions = new HashMap<>();

    private File rootDir;
    private File currentDir;
    private FileAdapter adapter;
    private ListView listView;
    private TextView emptyView;
    private TextView infoView;
    private View progressView;
    private LinearLayout pathBar;
    private HorizontalScrollView pathScroll;
    private SharedPreferences prefs;
    private Menu optionsMenu;

    private int sortMode;
    private boolean sortDescending;
    private boolean showHidden;
    private String searchQuery = "";

    private final List<File> clipboard = new ArrayList<>();
    private boolean clipboardIsMove;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        sortMode = prefs.getInt(KEY_SORT, SORT_NAME);
        sortDescending = prefs.getBoolean(KEY_SORT_DESC, false);
        showHidden = prefs.getBoolean(KEY_HIDDEN, false);

        listView = findViewById(R.id.list);
        emptyView = findViewById(R.id.empty);
        infoView = findViewById(R.id.info);
        progressView = findViewById(R.id.progress);
        pathBar = findViewById(R.id.path_bar);
        pathScroll = findViewById(R.id.path_scroll);

        adapter = new FileAdapter(this);
        listView.setAdapter(adapter);
        listView.setOnItemClickListener((parent, view, position, id) -> onFileClicked(adapter.getItem(position)));
        listView.setMultiChoiceModeListener(new SelectionListener());

        rootDir = Environment.getExternalStorageDirectory();
        currentDir = rootDir;
        if (savedInstanceState != null) {
            String saved = savedInstanceState.getString(STATE_DIR);
            if (saved != null && new File(saved).isDirectory()) currentDir = new File(saved);
        }

        if (hasStoragePermission()) {
            loadDirectory(currentDir);
        } else {
            requestStoragePermission();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Volta das configurações de "Acesso a todos os arquivos".
        if (hasStoragePermission() && adapter.getCount() == 0) loadDirectory(currentDir);
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putString(STATE_DIR, currentDir.getAbsolutePath());
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdownNow();
    }

    // ---------------------------------------------------------------- Permissões

    private boolean hasStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return Environment.isExternalStorageManager();
        }
        return checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestStoragePermission() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.permission_title)
                .setMessage(R.string.permission_message)
                .setCancelable(false)
                .setPositiveButton(R.string.grant, (d, w) -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        try {
                            startActivity(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                    Uri.parse("package:" + getPackageName())));
                        } catch (ActivityNotFoundException e) {
                            startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
                        }
                    } else {
                        requestPermissions(new String[]{
                                Manifest.permission.READ_EXTERNAL_STORAGE,
                                Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQUEST_PERMISSION);
                    }
                })
                .setNegativeButton(R.string.cancel, (d, w) -> finish())
                .show();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQUEST_PERMISSION) return;
        if (hasStoragePermission()) {
            loadDirectory(currentDir);
        } else {
            requestStoragePermission();
        }
    }

    // ---------------------------------------------------------------- Navegação

    private void navigateTo(File dir) {
        scrollPositions.put(currentDir.getAbsolutePath(), listView.getFirstVisiblePosition());
        collapseSearch();
        loadDirectory(dir);
    }

    private void loadDirectory(File dir) {
        if (!dir.canRead()) {
            toast(getString(R.string.cannot_read, dir.getName()));
            return;
        }
        currentDir = dir;
        updatePathBar();
        updateInfo();
        progressView.setVisibility(View.VISIBLE);

        final String query = searchQuery.toLowerCase(Locale.getDefault());
        final boolean hidden = showHidden;
        final int mode = sortMode;
        final boolean desc = sortDescending;
        executor.execute(() -> {
            List<File> result = new ArrayList<>();
            if (query.isEmpty()) {
                File[] children = dir.listFiles();
                if (children != null) {
                    for (File f : children) {
                        if (hidden || !f.isHidden()) result.add(f);
                    }
                }
            } else {
                search(dir, query, hidden, result, 0);
            }
            sortFiles(result, mode, desc);
            mainHandler.post(() -> {
                if (!dir.equals(currentDir)) return;
                progressView.setVisibility(View.GONE);
                adapter.setFiles(result, hidden);
                emptyView.setText(query.isEmpty() ? R.string.empty_folder : R.string.no_results);
                emptyView.setVisibility(result.isEmpty() ? View.VISIBLE : View.GONE);
                Integer pos = scrollPositions.remove(dir.getAbsolutePath());
                listView.setSelection(pos != null ? pos : 0);
            });
        });
    }

    private void search(File dir, String query, boolean hidden, List<File> out, int depth) {
        if (depth > 12 || out.size() >= 500 || Thread.currentThread().isInterrupted()) return;
        File[] children = dir.listFiles();
        if (children == null) return;
        for (File f : children) {
            if (!hidden && f.isHidden()) continue;
            if (f.getName().toLowerCase(Locale.getDefault()).contains(query)) out.add(f);
            if (f.isDirectory()) search(f, query, hidden, out, depth + 1);
        }
    }

    private static void sortFiles(List<File> files, int mode, boolean desc) {
        Comparator<File> byName = (a, b) -> a.getName().compareToIgnoreCase(b.getName());
        Comparator<File> cmp;
        switch (mode) {
            case SORT_DATE:
                cmp = (a, b) -> Long.compare(a.lastModified(), b.lastModified());
                break;
            case SORT_SIZE:
                cmp = (a, b) -> Long.compare(a.isDirectory() ? 0 : a.length(), b.isDirectory() ? 0 : b.length());
                break;
            case SORT_TYPE:
                cmp = (a, b) -> FileUtils.extension(a).compareTo(FileUtils.extension(b));
                break;
            default:
                cmp = byName;
        }
        final Comparator<File> primary = desc ? Collections.reverseOrder(cmp) : cmp;
        // Pastas sempre primeiro; empate resolvido pelo nome.
        Collections.sort(files, (a, b) -> {
            if (a.isDirectory() != b.isDirectory()) return a.isDirectory() ? -1 : 1;
            int r = primary.compare(a, b);
            return r != 0 ? r : byName.compare(a, b);
        });
    }

    private void refresh() {
        scrollPositions.put(currentDir.getAbsolutePath(), listView.getFirstVisiblePosition());
        loadDirectory(currentDir);
    }

    private void updatePathBar() {
        pathBar.removeAllViews();
        List<File> chain = new ArrayList<>();
        File f = currentDir;
        while (f != null) {
            chain.add(0, f);
            if (f.equals(rootDir)) break;
            f = f.getParentFile();
        }
        int pad = dp(8);
        for (int i = 0; i < chain.size(); i++) {
            final File dir = chain.get(i);
            if (i > 0) {
                TextView sep = new TextView(this);
                sep.setText("›");
                sep.setTextColor(getColor(R.color.text_secondary));
                sep.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
                pathBar.addView(sep);
            }
            TextView crumb = new TextView(this);
            String label = dir.equals(rootDir) ? getString(R.string.internal_storage)
                    : (dir.getName().isEmpty() ? "/" : dir.getName());
            crumb.setText(label);
            crumb.setPadding(pad, pad, pad, pad);
            crumb.setGravity(Gravity.CENTER_VERTICAL);
            crumb.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            boolean last = i == chain.size() - 1;
            crumb.setTextColor(getColor(last ? R.color.text_primary : R.color.text_secondary));
            if (!last) crumb.setOnClickListener(v -> navigateTo(dir));
            pathBar.addView(crumb);
        }
        pathScroll.post(() -> pathScroll.fullScroll(View.FOCUS_RIGHT));
    }

    private void updateInfo() {
        try {
            StatFs stat = new StatFs(currentDir.getAbsolutePath());
            long free = stat.getAvailableBytes();
            long total = stat.getTotalBytes();
            infoView.setText(getString(R.string.storage_info,
                    FileUtils.formatSize(free), FileUtils.formatSize(total)));
            infoView.setVisibility(View.VISIBLE);
        } catch (IllegalArgumentException e) {
            infoView.setVisibility(View.GONE);
        }
    }

    @Override
    public void onBackPressed() {
        if (!searchQuery.isEmpty()) {
            collapseSearch();
            loadDirectory(currentDir);
            return;
        }
        File parent = currentDir.getParentFile();
        if (!currentDir.equals(rootDir) && parent != null && parent.canRead()) {
            scrollPositions.remove(currentDir.getAbsolutePath());
            loadDirectory(parent);
        } else {
            super.onBackPressed();
        }
    }

    // ---------------------------------------------------------------- Abrir arquivos

    private void onFileClicked(File file) {
        if (file.isDirectory()) {
            navigateTo(file);
        } else {
            openFile(file, false);
        }
    }

    private Uri uriFor(File file) {
        return FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", file);
    }

    private void openFile(File file, boolean chooser) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(uriFor(file), FileUtils.mimeType(file));
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(chooser ? Intent.createChooser(intent, getString(R.string.open_with)) : intent);
        } catch (ActivityNotFoundException e) {
            if (!chooser) {
                openFile(file, true);
            } else {
                toast(getString(R.string.no_app));
            }
        } catch (IllegalArgumentException e) {
            toast(getString(R.string.no_app));
        }
    }

    private void shareFiles(List<File> files) {
        ArrayList<Uri> uris = new ArrayList<>();
        for (File f : files) {
            if (f.isFile()) uris.add(uriFor(f));
        }
        if (uris.isEmpty()) {
            toast(getString(R.string.share_folders));
            return;
        }
        Intent intent;
        if (uris.size() == 1) {
            intent = new Intent(Intent.ACTION_SEND);
            intent.putExtra(Intent.EXTRA_STREAM, uris.get(0));
            intent.setType(FileUtils.mimeType(files.get(0)));
        } else {
            intent = new Intent(Intent.ACTION_SEND_MULTIPLE);
            intent.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);
            intent.setType("*/*");
        }
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(Intent.createChooser(intent, getString(R.string.share)));
    }

    // ---------------------------------------------------------------- Menu principal

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.main, menu);
        optionsMenu = menu;
        menu.findItem(R.id.action_hidden).setChecked(showHidden);
        updatePasteItem();

        MenuItem searchItem = menu.findItem(R.id.action_search);
        SearchView searchView = (SearchView) searchItem.getActionView();
        searchView.setQueryHint(getString(R.string.search_hint));
        searchView.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        searchView.setOnQueryTextListener(new SearchView.OnQueryTextListener() {
            @Override
            public boolean onQueryTextSubmit(String query) {
                searchQuery = query.trim();
                loadDirectory(currentDir);
                searchView.clearFocus();
                return true;
            }

            @Override
            public boolean onQueryTextChange(String newText) {
                if (newText.isEmpty() && !searchQuery.isEmpty()) {
                    searchQuery = "";
                    loadDirectory(currentDir);
                }
                return false;
            }
        });
        searchItem.setOnActionExpandListener(new MenuItem.OnActionExpandListener() {
            @Override
            public boolean onMenuItemActionExpand(MenuItem item) {
                return true;
            }

            @Override
            public boolean onMenuItemActionCollapse(MenuItem item) {
                if (!searchQuery.isEmpty()) {
                    searchQuery = "";
                    loadDirectory(currentDir);
                }
                return true;
            }
        });
        return true;
    }

    private void collapseSearch() {
        searchQuery = "";
        if (optionsMenu != null) {
            MenuItem item = optionsMenu.findItem(R.id.action_search);
            if (item.isActionViewExpanded()) item.collapseActionView();
        }
    }

    private void updatePasteItem() {
        if (optionsMenu == null) return;
        MenuItem paste = optionsMenu.findItem(R.id.action_paste);
        paste.setVisible(!clipboard.isEmpty());
        paste.setTitle(getString(clipboardIsMove ? R.string.move_here : R.string.paste_here, clipboard.size()));
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_paste) {
            paste();
        } else if (id == R.id.action_new_folder) {
            promptCreate(true);
        } else if (id == R.id.action_new_file) {
            promptCreate(false);
        } else if (id == R.id.action_sort) {
            showSortDialog();
        } else if (id == R.id.action_hidden) {
            showHidden = !item.isChecked();
            item.setChecked(showHidden);
            prefs.edit().putBoolean(KEY_HIDDEN, showHidden).apply();
            refresh();
        } else if (id == R.id.action_home) {
            navigateTo(rootDir);
        } else if (id == R.id.action_refresh) {
            refresh();
        } else {
            return super.onOptionsItemSelected(item);
        }
        return true;
    }

    private void showSortDialog() {
        String[] options = getResources().getStringArray(R.array.sort_options);
        int checked = sortMode * 2 + (sortDescending ? 1 : 0);
        new AlertDialog.Builder(this)
                .setTitle(R.string.sort)
                .setSingleChoiceItems(options, checked, (d, which) -> {
                    sortMode = which / 2;
                    sortDescending = which % 2 == 1;
                    prefs.edit().putInt(KEY_SORT, sortMode).putBoolean(KEY_SORT_DESC, sortDescending).apply();
                    d.dismiss();
                    refresh();
                })
                .show();
    }

    // ---------------------------------------------------------------- Operações

    private void promptCreate(boolean folder) {
        showNameDialog(getString(folder ? R.string.new_folder : R.string.new_file), "", name -> {
            File target = new File(currentDir, name);
            if (target.exists()) {
                toast(getString(R.string.already_exists));
                return;
            }
            boolean ok;
            try {
                ok = folder ? target.mkdir() : target.createNewFile();
            } catch (IOException e) {
                ok = false;
            }
            if (!ok) toast(getString(R.string.operation_failed));
            refresh();
        });
    }

    private void promptRename(File file) {
        showNameDialog(getString(R.string.rename), file.getName(), name -> {
            if (name.equals(file.getName())) return;
            File target = new File(file.getParentFile(), name);
            if (target.exists()) {
                toast(getString(R.string.already_exists));
            } else if (!file.renameTo(target)) {
                toast(getString(R.string.operation_failed));
            }
            refresh();
        });
    }

    private interface NameCallback {
        void onName(String name);
    }

    private void showNameDialog(String title, String initial, NameCallback callback) {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setHint(R.string.name);
        input.setText(initial);
        int dot = initial.lastIndexOf('.');
        input.setSelection(0, dot > 0 ? dot : initial.length());

        FrameLayout container = new FrameLayout(this);
        int pad = dp(20);
        container.setPadding(pad, dp(8), pad, 0);
        container.addView(input);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(title)
                .setView(container)
                .setPositiveButton(R.string.ok, (d, w) -> {
                    String name = input.getText().toString().trim();
                    if (FileUtils.isValidName(name)) {
                        callback.onName(name);
                    } else {
                        toast(getString(R.string.invalid_name));
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .create();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
        }
        dialog.show();
        input.requestFocus();
    }

    private void confirmDelete(List<File> files) {
        String message = files.size() == 1
                ? getString(R.string.confirm_delete_one, files.get(0).getName())
                : getString(R.string.confirm_delete_many, files.size());
        new AlertDialog.Builder(this)
                .setTitle(R.string.delete)
                .setMessage(message)
                .setPositiveButton(R.string.delete, (d, w) -> runOperation(R.string.deleting, () -> {
                    int failed = 0;
                    for (File f : files) {
                        if (!FileUtils.deleteRecursive(f)) failed++;
                    }
                    return failed;
                }))
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void setClipboard(List<File> files, boolean move) {
        clipboard.clear();
        clipboard.addAll(files);
        clipboardIsMove = move;
        updatePasteItem();
        toast(getResources().getQuantityString(R.plurals.selected, files.size(), files.size())
                + " — " + getString(R.string.navigate_and_paste));
    }

    private void paste() {
        final File destDir = currentDir;
        final List<File> sources = new ArrayList<>(clipboard);
        final boolean move = clipboardIsMove;
        for (File src : sources) {
            if (src.isDirectory() && FileUtils.isInside(destDir, src)) {
                toast(getString(R.string.paste_into_itself));
                return;
            }
        }
        if (move) {
            clipboard.clear();
            updatePasteItem();
        }
        runOperation(move ? R.string.moving : R.string.copying, () -> {
            int failed = 0;
            for (File src : sources) {
                if (!src.exists()) {
                    failed++;
                    continue;
                }
                try {
                    if (move && destDir.equals(src.getParentFile())) continue;
                    File target = FileUtils.uniqueTarget(destDir, src.getName());
                    if (move) {
                        FileUtils.moveRecursive(src, target);
                    } else {
                        FileUtils.copyRecursive(src, target);
                    }
                } catch (IOException e) {
                    failed++;
                }
            }
            return failed;
        });
    }

    private interface Operation {
        /** Executa em segundo plano e retorna o número de falhas. */
        int run();
    }

    private void runOperation(int messageRes, Operation operation) {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.HORIZONTAL);
        layout.setGravity(Gravity.CENTER_VERTICAL);
        int pad = dp(24);
        layout.setPadding(pad, pad, pad, pad);
        android.widget.ProgressBar bar = new android.widget.ProgressBar(this);
        TextView text = new TextView(this);
        text.setText(messageRes);
        text.setPadding(pad, 0, 0, 0);
        text.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        layout.addView(bar);
        layout.addView(text);
        AlertDialog dialog = new AlertDialog.Builder(this).setView(layout).setCancelable(false).show();

        executor.execute(() -> {
            int failed = operation.run();
            mainHandler.post(() -> {
                if (isFinishing() || isDestroyed()) return;
                dialog.dismiss();
                if (failed > 0) toast(getString(R.string.some_failed, failed));
                refresh();
            });
        });
    }

    private void showProperties(List<File> files) {
        TextView view = new TextView(this);
        int pad = dp(24);
        view.setPadding(pad, dp(12), pad, 0);
        view.setTextIsSelectable(true);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);

        StringBuilder sb = new StringBuilder();
        if (files.size() == 1) {
            File f = files.get(0);
            sb.append(getString(R.string.prop_name)).append(": ").append(f.getName()).append('\n');
            sb.append(getString(R.string.prop_path)).append(": ").append(f.getAbsolutePath()).append('\n');
            sb.append(getString(R.string.prop_type)).append(": ")
                    .append(f.isDirectory() ? getString(R.string.folder) : FileUtils.mimeType(f)).append('\n');
            sb.append(getString(R.string.prop_modified)).append(": ")
                    .append(FileUtils.formatDate(f.lastModified())).append('\n');
            sb.append(getString(R.string.prop_permissions)).append(": ")
                    .append(f.canRead() ? "r" : "-").append(f.canWrite() ? "w" : "-")
                    .append(f.canExecute() ? "x" : "-").append('\n');
            if (f.isDirectory()) {
                int count = FileUtils.countChildren(f, true);
                sb.append(getString(R.string.prop_contents)).append(": ")
                        .append(getResources().getQuantityString(R.plurals.items, count, count)).append('\n');
            }
        } else {
            sb.append(getResources().getQuantityString(R.plurals.selected, files.size(), files.size())).append('\n');
        }
        final String header = sb.toString();
        final String sizeLabel = getString(R.string.prop_size) + ": ";
        view.setText(header + sizeLabel + getString(R.string.calculating));

        new AlertDialog.Builder(this)
                .setTitle(R.string.properties)
                .setView(view)
                .setPositiveButton(R.string.ok, null)
                .show();

        executor.execute(() -> {
            long total = 0;
            for (File f : files) total += FileUtils.sizeOf(f);
            final long size = total;
            mainHandler.post(() -> view.setText(header + sizeLabel + FileUtils.formatSize(size)
                    + String.format(Locale.getDefault(), " (%,d bytes)", size)));
        });
    }

    // ---------------------------------------------------------------- Seleção múltipla

    private List<File> checkedFiles() {
        List<File> result = new ArrayList<>();
        SparseBooleanArray checked = listView.getCheckedItemPositions();
        for (int i = 0; i < checked.size(); i++) {
            if (checked.valueAt(i)) result.add(adapter.getItem(checked.keyAt(i)));
        }
        return result;
    }

    private class SelectionListener implements AbsListView.MultiChoiceModeListener {

        @Override
        public boolean onCreateActionMode(ActionMode mode, Menu menu) {
            mode.getMenuInflater().inflate(R.menu.selection, menu);
            return true;
        }

        @Override
        public boolean onPrepareActionMode(ActionMode mode, Menu menu) {
            boolean single = listView.getCheckedItemCount() == 1;
            menu.findItem(R.id.sel_rename).setVisible(single);
            return true;
        }

        @Override
        public void onItemCheckedStateChanged(ActionMode mode, int position, long id, boolean checked) {
            int count = listView.getCheckedItemCount();
            mode.setTitle(getResources().getQuantityString(R.plurals.selected, count, count));
            mode.invalidate();
        }

        @Override
        public boolean onActionItemClicked(ActionMode mode, MenuItem item) {
            int id = item.getItemId();
            if (id == R.id.sel_all) {
                for (int i = 0; i < adapter.getCount(); i++) listView.setItemChecked(i, true);
                return true;
            }
            List<File> files = checkedFiles();
            if (files.isEmpty()) return true;
            if (id == R.id.sel_copy) {
                setClipboard(files, false);
            } else if (id == R.id.sel_move) {
                setClipboard(files, true);
            } else if (id == R.id.sel_share) {
                shareFiles(files);
            } else if (id == R.id.sel_delete) {
                confirmDelete(files);
            } else if (id == R.id.sel_rename) {
                promptRename(files.get(0));
            } else if (id == R.id.sel_properties) {
                showProperties(files);
            } else {
                return false;
            }
            mode.finish();
            return true;
        }

        @Override
        public void onDestroyActionMode(ActionMode mode) {
        }
    }

    // ---------------------------------------------------------------- Utilidades

    private int dp(int value) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, getResources().getDisplayMetrics());
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }
}
