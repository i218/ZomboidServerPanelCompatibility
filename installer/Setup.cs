using System;
using System.IO;
using System.IO.Compression;
using System.Reflection;
using System.Text;
using System.Text.RegularExpressions;
using System.Collections.Generic;
using System.Security.Cryptography;
using System.Windows.Forms;
using System.Drawing;
[assembly: AssemblyVersion("0.4.1.0")]
[assembly: AssemblyTitle("ServerPanelBridge Setup")]
public class Setup {
    const string Id="ServerPanelBridge";
    static readonly Encoding Utf8=new UTF8Encoding(false,true);
    static string DefaultDir { get { return Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.UserProfile),"Zomboid"); } }
    [STAThread] public static int Main(string[] args) {
        try {
            if(args.Length==0) { Application.EnableVisualStyles(); Application.Run(new InstallForm()); return 0; }
            var options=new Dictionary<string,string>();
            for(int i=0;i<args.Length;i++) {
                if(args[i]=="--silent") continue;
                string key=args[i];
                if(key!="--data-dir" && key!="--server-name" && key!="--mode") throw new Exception("Unknown option: "+key);
                if(++i==args.Length) throw new Exception("Missing option value");
                options.Add(key,args[i]);
            }
            string mode=Get(options,"--mode","server");
            if(mode!="server" && mode!="client" && mode!="both") throw new Exception("Invalid mode");
            Install(Get(options,"--data-dir",DefaultDir),Get(options,"--server-name","servertest"),mode!="client",mode!="server");
            return 0;
        } catch(Exception error) {
            string log=Path.Combine(Path.GetTempPath(),Id+"-setup-error-"+Guid.NewGuid().ToString("N")+".txt");
            try { File.WriteAllText(log,error.ToString()); } catch {}
            if(Array.IndexOf(args,"--silent")<0) MessageBox.Show(error.Message+"\nОтчёт: "+log,Id);
            return 1;
        }
    }
    static string Get(Dictionary<string,string> options,string key,string fallback) { return options.ContainsKey(key)?options[key]:fallback; }
    static byte[] Encode(string text,byte[] old) {
        byte[] result=Utf8.GetBytes(text);
        if(old!=null && old.Length>=3 && old[0]==239 && old[1]==187 && old[2]==191) {
            byte[] bom=new byte[result.Length+3]; bom[0]=239; bom[1]=187; bom[2]=191; Buffer.BlockCopy(result,0,bom,3,result.Length); return bom;
        }
        return result;
    }
    static string Decode(byte[] data) {
        int start=data.Length>=3 && data[0]==239 && data[1]==187 && data[2]==191?3:0;
        return Utf8.GetString(data,start,data.Length-start);
    }
    public static string EnableServer(string text) {
        var matches=Regex.Matches(text,@"(?m)^Mods=([^\r\n]*)");
        if(matches.Count>1) throw new IOException("Несколько строк Mods в INI; установка отменена.");
        string value=matches.Count==0?"":matches[0].Groups[1].Value;
        var ids=new List<string>(value.Split(new[]{';'},StringSplitOptions.RemoveEmptyEntries));
        bool slash=value.Length==0 || value.Contains("\\");
        foreach(string required in new[]{"ZombieBuddy",Id}) {
            bool present=false; foreach(string id in ids) if(id.Trim().TrimStart('\\')==required) present=true;
            if(!present) ids.Add((slash?"\\":"")+required);
        }
        string line="Mods="+String.Join(";",ids);
        if(matches.Count==1) return text.Remove(matches[0].Index,matches[0].Length).Insert(matches[0].Index,line);
        string nl=text.Contains("\r\n")?"\r\n":"\n";
        return text+(text.Length==0 || text.EndsWith("\n")?"":nl)+line+nl;
    }
    public static string EnableClient(string text) {
        var blocks=Regex.Matches(text,@"(?ms)^\s*mods\s*\{(?<body>[^{}]*)\}");
        if(blocks.Count!=1) throw new IOException("Не удалось прочитать mods/default.txt; установка отменена.");
        bool core=Regex.IsMatch(blocks[0].Groups["body"].Value,@"(?m)^\s*mod\s*=\s*\\?ServerPanelBridge\s*,?\s*$");
        bool menu=Regex.IsMatch(blocks[0].Groups["body"].Value,@"(?m)^\s*mod\s*=\s*\\?ServerPanelBridgeMenu\s*,?\s*$");
        string nl=text.Contains("\r\n")?"\r\n":"\n";
        return text.Insert(blocks[0].Index+blocks[0].Length-1,(core?"":"    mod = ServerPanelBridge,"+nl)+(menu?"":"    mod = ServerPanelBridgeMenu,"+nl));
    }
    static void SafePath(string path,string root) {
        string full=Path.GetFullPath(path), boundary=Path.GetFullPath(root).TrimEnd('\\','/')+Path.DirectorySeparatorChar;
        if(!full.StartsWith(boundary,StringComparison.OrdinalIgnoreCase)) throw new IOException("Путь выходит за папку данных: "+path);
        for(string current=full;current!=null;current=Path.GetDirectoryName(current)) {
            if((File.Exists(current)||Directory.Exists(current)) && (File.GetAttributes(current)&FileAttributes.ReparsePoint)!=0)
                throw new IOException("Установка через junction/symlink не поддерживается; укажите фактический путь: "+current);
            if(String.Equals(current,Path.GetFullPath(root),StringComparison.OrdinalIgnoreCase)) break;
        }
    }
    static void WriteAtomic(string path,byte[] bytes) {
        Directory.CreateDirectory(Path.GetDirectoryName(path));
        string temp=path+".spb-"+Guid.NewGuid().ToString("N");
        FileAttributes attributes=File.Exists(path)?File.GetAttributes(path):FileAttributes.Normal;
        try {
            File.WriteAllBytes(temp,bytes);
            if(File.Exists(path)) {
                File.SetAttributes(path,attributes & ~FileAttributes.ReadOnly);
                File.Replace(temp,path,null);
                File.SetAttributes(path,attributes);
            } else File.Move(temp,path);
        } catch(Exception error) {
            if(File.Exists(path)) try { File.SetAttributes(path,attributes); } catch {}
            throw new IOException("Не удалось записать файл: "+path+"\\n"+error.Message,error);
        } finally { if(File.Exists(temp)) File.Delete(temp); }
    }
    public static void Install(string dataDir,string serverName,bool server,bool client) {
        if(!server && !client) throw new Exception("Выберите установку для сервера или клиента.");
        if(String.IsNullOrWhiteSpace(dataDir) || !Path.IsPathRooted(dataDir)) throw new Exception("Укажите полный путь cachedir.");
        dataDir=Path.GetFullPath(dataDir).TrimEnd('\\','/');
        if(dataDir.Length<=3) throw new Exception("Корень диска не подходит.");
        if(server && (!Regex.IsMatch(serverName??"", @"^[A-Za-z0-9_.-]+$") || serverName=="." || serverName==".."))
            throw new Exception("Недопустимое имя сервера.");
        string target=Path.Combine(dataDir,"mods",Id);
        SafePath(target,dataDir);
        if(File.Exists(target)) throw new IOException("Папка мода занята файлом: "+target);
        if(Directory.Exists(target)) {
            string info=Path.Combine(target,"42","mod.info");
            if(!File.Exists(info) || !Regex.IsMatch(File.ReadAllText(info),@"(?m)^id=ServerPanelBridge\r?$"))
                throw new IOException("Не подтверждена существующая установка: "+target);
        }
        var writes=new Dictionary<string,byte[]>();
        string ini=Path.Combine(dataDir,"Server",(serverName??"")+".ini");
        if(server) {
            SafePath(ini,dataDir);
            if(!File.Exists(ini)) throw new IOException("INI сервера не найден: "+ini+"\nВыберите существующий сервер.");
            byte[] old=File.ReadAllBytes(ini); writes.Add(ini,Encode(EnableServer(Decode(old)),old));
        }
        if(client) {
            string profile=Path.Combine(dataDir,"mods","default.txt"); SafePath(profile,dataDir);
            byte[] old=File.Exists(profile)?File.ReadAllBytes(profile):null;
            writes.Add(profile,Encode(EnableClient(old==null?"VERSION = 1,\nmods\n{\n}\nmaps\n{\n}\n":Decode(old)),old));
        }
        using(var stream=Assembly.GetExecutingAssembly().GetManifestResourceStream("payload.zip"))
        using(var zip=new ZipArchive(stream,ZipArchiveMode.Read)) {
            foreach(string file in new[]{"42/mod.info","42/media/java/ServerPanelBridge.jar","config/ServerPanelBridge.properties"})
                if(zip.GetEntry(Id+"/"+file)==null) throw new IOException("Повреждён встроенный архив: "+file);
            foreach(var entry in zip.Entries) {
                if(entry.FullName.EndsWith("/")) continue;
                if(!entry.FullName.StartsWith(Id+"/",StringComparison.Ordinal)) throw new IOException("Неверный payload");
                string relative=entry.FullName.Substring(Id.Length+1);
                string destinationRoot=target;
                if(relative.StartsWith("client-support/",StringComparison.Ordinal)) {
                    if(!client) continue;
                    string prefix="client-support/ServerPanelBridgeMenu/";
                    if(!relative.StartsWith(prefix,StringComparison.Ordinal)) throw new IOException("Unknown client component");
                    relative=relative.Substring(prefix.Length);
                    destinationRoot=Path.Combine(dataDir,"mods","ServerPanelBridgeMenu");
                    string existingInfo=Path.Combine(destinationRoot,"42","mod.info");
                    if(Directory.Exists(destinationRoot) && (!File.Exists(existingInfo) || !Regex.IsMatch(File.ReadAllText(existingInfo),@"(?m)^id=ServerPanelBridgeMenu\r?$"))) throw new IOException("Unrecognized menu component: "+destinationRoot);
                }
                string dest=Path.GetFullPath(Path.Combine(destinationRoot,relative.Replace('/',Path.DirectorySeparatorChar)));
                SafePath(dest,destinationRoot);
                using(var input=entry.Open()) using(var memory=new MemoryStream()) { input.CopyTo(memory); writes.Add(dest,memory.ToArray()); }
            }
            byte[] jar=writes[Path.Combine(target,"42","media","java",Id+".jar")];
            using(var memory=new MemoryStream(jar)) using(var archive=new ZipArchive(memory,ZipArchiveMode.Read))
                if(archive.GetEntry("io/serverpanelbridge/Main.class")==null) throw new IOException("Повреждён встроенный JAR");
            if(server) {
                string config=Path.Combine(dataDir,Id,serverName,Id+".properties"); SafePath(config,dataDir);
                if(!File.Exists(config)) writes.Add(config,writes[Path.Combine(target,"config",Id+".properties")]);
            }
        }
        var originals=new Dictionary<string,byte[]>(); var changed=new List<string>();
        string backup=Path.Combine(dataDir,Id+"-installer-backups",Guid.NewGuid().ToString("N"));
        SafePath(backup,dataDir);
        // Validate and back up every destination before the first replacement.
        foreach(var write in writes) {
            SafePath(write.Key,dataDir);
            byte[] original=File.Exists(write.Key)?File.ReadAllBytes(write.Key):null;
            originals.Add(write.Key,original);
            if(original!=null) {
                string saved=Path.Combine(backup,write.Key.Substring(dataDir.Length+1));
                Directory.CreateDirectory(Path.GetDirectoryName(saved)); File.WriteAllBytes(saved,original);
            }
        }
        try {
            foreach(var write in writes) {
                changed.Add(write.Key);
                WriteAtomic(write.Key,write.Value);
                using(var hash=SHA256.Create()) {
                    string expected=BitConverter.ToString(hash.ComputeHash(write.Value));
                    if(expected!=BitConverter.ToString(hash.ComputeHash(File.ReadAllBytes(write.Key)))) throw new IOException("Проверка записи не пройдена: "+write.Key);
                }
            }
        } catch(Exception failure) {
            var errors=new List<string>();
            for(int i=changed.Count-1;i>=0;i--) try {
                string path=changed[i]; if(originals[path]==null) File.Delete(path); else WriteAtomic(path,originals[path]);
            } catch(Exception error) { errors.Add(error.Message); }
            throw new IOException("Установка отменена. Backup: "+backup+"\n"+failure.Message+(errors.Count==0?"":"\nОшибки восстановления: "+String.Join("\n",errors)),failure);
        }
    }
    class InstallForm:Form {
        TextBox path=new TextBox(); ComboBox name=new ComboBox();
        CheckBox server=new CheckBox(), client=new CheckBox();
        public InstallForm() {
            Text="ServerPanelBridge 0.4.1 — установка"; ClientSize=new Size(580,270); FormBorderStyle=FormBorderStyle.FixedDialog; MaximizeBox=false;
            var label=new Label { Text="Папка данных Zomboid (cachedir), не папка игры:",AutoSize=true,Location=new Point(15,15) }; Controls.Add(label);
            path.Text=DefaultDir; path.SetBounds(15,40,450,25); Controls.Add(path);
            var browse=new Button {Text="Обзор",Location=new Point(475,40)}; Controls.Add(browse);
            browse.Click+=(s,e)=>{using(var dialog=new FolderBrowserDialog()) if(dialog.ShowDialog()==DialogResult.OK) path.Text=dialog.SelectedPath;};
            server.Text="Настроить dedicated server"; server.Checked=true; server.SetBounds(15,80,260,25); Controls.Add(server);
            name.SetBounds(280,80,280,25); name.Text="servertest"; Controls.Add(name);
            path.TextChanged+=(s,e)=>RefreshServers(); RefreshServers();
            client.Text="Также включить в клиентском списке модов (необязательно)"; client.SetBounds(15,115,550,25); Controls.Add(client);
            var note=new Label {Text="JAR встроен. Настройки сохраняются. Игра и сервер должны быть закрыты.\nНужен установленный ZombieBuddy Java agent; доверие JAR — через ZombieBuddy.\nServerPanel устанавливается отдельно. Клиентам подключаемой игры мод не нужен.",Location=new Point(15,150),Size=new Size(550,60)}; Controls.Add(note);
            var install=new Button {Text="Установить",Location=new Point(425,225),Size=new Size(135,30)}; Controls.Add(install);
            install.Click+=(s,e)=>{try {
                Install(path.Text,name.Text,server.Checked,client.Checked);
                MessageBox.Show("Установлено. JAR распакован и проверен.\nЗапустите сервер заново; подтвердите доверие JAR в ZombieBuddy при необходимости.","ServerPanelBridge");
            } catch(Exception error) {MessageBox.Show(error.Message,"Ошибка установки");}};
        }
        void RefreshServers() {
            string selected=name.Text; name.Items.Clear();
            string folder=Path.Combine(path.Text,"Server");
            try { if(Directory.Exists(folder)) foreach(string ini in Directory.GetFiles(folder,"*.ini")) name.Items.Add(Path.GetFileNameWithoutExtension(ini)); } catch {}
            if(name.Items.Count==1) name.SelectedIndex=0; else name.Text=selected;
        }
    }
}
