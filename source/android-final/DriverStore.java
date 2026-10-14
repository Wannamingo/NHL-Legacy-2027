package org.nhllegacy.port;

import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.*;

/** Private, bounded AdrenoTools ZIP store. No Android system files are changed. */
public final class DriverStore {
    public static final String SYSTEM = "system";
    private static final long ZIP_LIMIT = 128L * 1024 * 1024;
    private static final long DATA_LIMIT = 256L * 1024 * 1024;
    private final File root;
    private final int api;
    public static final class Driver {
        public final String id, name, version, author, library;
        public final File directory;
        Driver(String id, JSONObject meta, File directory) throws Exception {
            this.id=id; this.directory=directory;
            name=label(meta.getString("name"));
            version=label(meta.optString("driverVersion", meta.optString("packageVersion", "")));
            author=label(meta.optString("author", ""));
            library=meta.getString("libraryName");
        }
        public String toString() { return version.isEmpty() ? name : name+" — "+version; }
    }
    public DriverStore(File files, int api) throws IOException {
        this.root=new File(files, "gpu-drivers"); this.api=api;
        if (!root.isDirectory() && !root.mkdirs()) throw new IOException("Не удалось создать папку драйверов");
    }
    private static String label(String value) throws IOException {
        if (value.length()>160 || value.indexOf('\0')>=0) throw new IOException("Слишком длинное название драйвера");
        for (int i=0; i<value.length(); i++) if (Character.isISOControl(value.charAt(i)))
            throw new IOException("Недопустимое название драйвера");
        return value;
    }
    private static String hex(byte[] data) {
        StringBuilder out=new StringBuilder();
        for (byte b:data) out.append(String.format(Locale.ROOT,"%02x",b&255));
        return out.toString();
    }
    private static String hash(File file) throws Exception {
        MessageDigest digest=MessageDigest.getInstance("SHA-256");
        byte[] buffer=new byte[65536];
        try(InputStream in=new FileInputStream(file)) { int n; while((n=in.read(buffer))!=-1) digest.update(buffer,0,n); }
        return hex(digest.digest());
    }
    private static String safePath(String path) throws IOException {
        if (path.isEmpty() || path.length()>240 || path.startsWith("/") || path.indexOf('\\')>=0
            || path.indexOf(':')>=0 || path.indexOf('\0')>=0) throw new IOException("Недопустимый путь в ZIP");
        for(String part:path.split("/",-1)) if (part.equals("..") || part.equals(".") || part.isEmpty())
            throw new IOException("Недопустимый путь в ZIP");
        return path;
    }
    private static void elf(File file) throws IOException {
        byte[] h=new byte[64];
        try(DataInputStream in=new DataInputStream(new FileInputStream(file))) { in.readFully(h); }
        if (h[0]!=0x7f || h[1]!='E' || h[2]!='L' || h[3]!='F' || h[4]!=2 || h[5]!=1
            || h[6]!=1 || (h[16]&255)!=3 || h[17]!=0 || (h[18]&255)!=183 || h[19]!=0)
            throw new IOException("Нужен драйвер Android ARM64 (.so), совместимый с AdrenoTools");
    }
    private File child(File directory,String path) throws IOException {
        File file=new File(directory,safePath(path));
        if (!file.getCanonicalPath().startsWith(directory.getCanonicalPath()+File.separator))
            throw new IOException("Путь выходит из папки драйвера");
        return file;
    }
    private static JSONObject json(File file) throws Exception {
        if (!file.isFile() || file.length()>65536) throw new IOException("Отсутствует или повреждён meta.json");
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        try(InputStream in=new FileInputStream(file)) { byte[] b=new byte[4096];int n;while((n=in.read(b))!=-1)bytes.write(b,0,n); }
        return new JSONObject(new String(bytes.toByteArray(), StandardCharsets.UTF_8));
    }
    private void validateMeta(JSONObject meta) throws Exception {
        if (meta.getInt("schemaVersion")!=1) throw new IOException("Неподдерживаемая версия meta.json");
        label(meta.getString("name"));
        if (meta.getString("name").trim().isEmpty()) throw new IOException("Не указано название драйвера");
        String library=meta.getString("libraryName");
        if (!library.matches("[A-Za-z0-9_+.-]+\\.so")) throw new IOException("Неверное имя библиотеки драйвера");
        int min=meta.getInt("minApi");
        if (min<1 || min>api) throw new IOException("Драйвер требует Android API "+min+", устройство: "+api);
    }
    public synchronized Driver find(String id) throws Exception {
        if (!id.matches("[0-9a-f]{64}")) throw new IOException("Драйвер не выбран");
        File directory=child(root,id);
        JSONObject meta=json(new File(directory,"meta.json")); validateMeta(meta);
        JSONObject hashes=json(new File(directory,"installed-files.json"));
        Iterator<String> names=hashes.keys();int count=0;
        while(names.hasNext()) {
            String name=names.next(); File file=child(directory,name);
            if (++count>256 || !file.isFile() || !hash(file).equals(hashes.getString(name)))
                throw new IOException("Файлы драйвера повреждены; импортируйте ZIP заново");
        }
        if (count==0 || !hashes.has(meta.getString("libraryName")) || !hashes.has("meta.json"))
            throw new IOException("Неполный комплект драйвера");
        elf(child(directory,meta.getString("libraryName")));
        return new Driver(id,meta,directory);
    }
    public synchronized List<Driver> list() {
        List<Driver> drivers=new ArrayList<>();File[] children=root.listFiles();
        if(children!=null)for(File file:children)if(file.isDirectory() && file.getName().matches("[0-9a-f]{64}")) {
            try { drivers.add(find(file.getName())); } catch(Exception ignored) { }
        }
        Collections.sort(drivers,(a,b)->a.toString().compareToIgnoreCase(b.toString()));return drivers;
    }
    public synchronized Driver install(InputStream source) throws Exception {
        File archive=new File(root,".import-"+UUID.randomUUID()+".zip");
        File stage=new File(root,".stage-"+UUID.randomUUID());
        try {
            long compressed=0;
            try(OutputStream out=new FileOutputStream(archive)) {
                byte[] b=new byte[65536];int n;
                while((n=source.read(b))!=-1) { compressed+=n;if(compressed>ZIP_LIMIT)throw new IOException("ZIP драйвера больше 128 МБ");out.write(b,0,n); }
            }
            String id=hash(archive);File target=child(root,id);
            if(target.exists())return find(id);
            if(!stage.mkdir())throw new IOException("Не удалось подготовить импорт");
            JSONObject hashes=new JSONObject();
            try(ZipFile zip=new ZipFile(archive)) {
                List<ZipEntry> files=new ArrayList<>(); Set<String> seen=new HashSet<>();
                Enumeration<? extends ZipEntry> entries=zip.entries();String prefix=null;int entryCount=0;
                while(entries.hasMoreElements()) {
                    ZipEntry entry=entries.nextElement();
                    if(++entryCount>512)throw new IOException("Слишком много файлов в ZIP");
                    String name=entry.getName();String checked=entry.isDirectory() && name.endsWith("/")?name.substring(0,name.length()-1):name;
                    safePath(checked);
                    if(!seen.add(checked))throw new IOException("Повторяющийся путь в ZIP");
                    if(entry.isDirectory())continue;
                    if(files.size()>=256)throw new IOException("Слишком много файлов в ZIP");
                    files.add(entry);
                    if(name.equals("meta.json") || name.endsWith("/meta.json")) {
                        if(prefix!=null)throw new IOException("Неоднозначный meta.json");
                        prefix=name.substring(0,name.length()-"meta.json".length());
                    }
                }
                if(prefix==null)throw new IOException("Это не ZIP AdrenoTools: отсутствует meta.json");
                long total=0;
                for(ZipEntry entry:files) {
                    String name=entry.getName();
                    if(!name.startsWith(prefix))throw new IOException("Файлы драйвера находятся вне папки meta.json");
                    name=name.substring(prefix.length());
                    if(name.equals("installed-files.json"))throw new IOException("Зарезервированное имя файла");
                    File file=child(stage,name);File parent=file.getParentFile();
                    if(!parent.isDirectory()&&!parent.mkdirs())throw new IOException("Не удалось создать папку");
                    long written=0;CRC32 crc=new CRC32();
                    try(InputStream in=zip.getInputStream(entry);OutputStream out=new FileOutputStream(file)) {
                        byte[] b=new byte[65536];int n;
                        while((n=in.read(b))!=-1) { total+=n;written+=n;if(total>DATA_LIMIT || (name.equals("meta.json")&&written>65536))throw new IOException("Распакованный драйвер слишком большой");crc.update(b,0,n);out.write(b,0,n); }
                    }
                    if(entry.getSize()!=written || entry.getCrc()!=crc.getValue())throw new IOException("Повреждённый ZIP драйвера");
                    hashes.put(name,hash(file));
                }
            }
            JSONObject meta=json(new File(stage,"meta.json"));validateMeta(meta);
            new Driver(id,meta,stage);
            elf(child(stage,meta.getString("libraryName")));
            for(Iterator<String> it=hashes.keys();it.hasNext();) {
                String name=it.next();if(name.endsWith(".so") || name.contains(".so."))elf(child(stage,name));
            }
            byte[] manifest=hashes.toString().getBytes(StandardCharsets.UTF_8);
            if(manifest.length>65536)throw new IOException("Слишком длинные пути файлов драйвера");
            try(OutputStream out=new FileOutputStream(new File(stage,"installed-files.json"))) {
                out.write(manifest);
            }
            if(!stage.renameTo(target))throw new IOException("Не удалось завершить импорт драйвера");
            return find(id);
        } finally { if(archive.exists())removeOwned(archive);if(stage.exists())removeOwned(stage); }
    }
    public synchronized void remove(String id) throws Exception {
        if(!id.matches("[0-9a-f]{64}"))throw new IOException("Драйвер не выбран");
        File file=child(root,id);if(file.exists())removeOwned(file);
    }
    private void removeOwned(File file) throws IOException {
        if(!file.getCanonicalPath().startsWith(root.getCanonicalPath()+File.separator))throw new IOException("Удаление за пределами папки драйверов");
        File[] children=file.listFiles();if(children!=null)for(File child:children)removeOwned(child);
        if(!file.delete())throw new IOException("Не удалось удалить временный файл драйвера");
    }
}
