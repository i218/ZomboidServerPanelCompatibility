using System.Diagnostics;
using System.Net;
using System.Net.Http.Json;
using System.Reflection;
using Microsoft.AspNetCore.Builder;
using ServerPanel.App.Services;
using ServerPanel.App.Models;

if (args.Length != 2) throw new ArgumentException("Arguments: java executable, bridge project root");
var registry = new HostRegistry(); // isolated memory only; no user database
await using var panel = new PanelApiServer(registry, 0);
var queued = new HashSet<string>();
registry.HeartbeatRecorded += (_, e) => {
    lock(queued) {
        if(e.Host.SessionId is null || !queued.Add(e.Host.Id)) return;
        if(e.Host.Id == "pz-integration-test") {
            registry.EnqueueCommand(e.Host.Id,"start");registry.EnqueueCommand(e.Host.Id,"stop");registry.EnqueueCommand(e.Host.Id,"restart");
        } else if(e.Host.Id == "pz-restart-integration-test") registry.EnqueueCommand(e.Host.Id,"restart");
    }
};
await panel.StartAsync();
var field = typeof(PanelApiServer).GetField("_application", BindingFlags.NonPublic | BindingFlags.Instance)!;
var app = (WebApplication)field.GetValue(panel)!;
var url = app.Urls.Single();
var classpath=Path.Combine(args[1],"build/classes/java/main")+Path.PathSeparator+Path.Combine(args[1],"build/classes/java/test");
async Task RunJava(string main,params string[] more) {
    var info=new ProcessStartInfo(args[0]){UseShellExecute=false};info.ArgumentList.Add("-cp");info.ArgumentList.Add(classpath);info.ArgumentList.Add(main);info.ArgumentList.Add(url);
    foreach(var value in more)info.ArgumentList.Add(value);
    using var process=Process.Start(info)!;using var deadline=new CancellationTokenSource(TimeSpan.FromSeconds(25));
    try{await process.WaitForExitAsync(deadline.Token);}catch{if(!process.HasExited)process.Kill();throw;} // only this test child
    if(process.ExitCode!=0)throw new InvalidOperationException("Java protocol fixture failed");
}
await RunJava("io.serverpanelbridge.PanelSmoke");
var host=registry.GetHost("pz-integration-test")??throw new InvalidOperationException("No registered host");
if(host.Name!="Dedicated integration test" || host.Category!="Game servers" || host.Health!="Healthy" || host.Version!="ServerPanelBridge 0.4.1" || host.CpuPercent<0 || host.CpuPercent>100 || host.MemoryMb<=0 || host.SessionId is null)
    throw new InvalidOperationException("Unexpected heartbeat values");
var history=registry.GetCommandHistory(host.Id);
if(history.Count!=3 || history.Single(c=>c.Action=="stop").Status!="Acknowledged" || history.Where(c=>c.Action!="stop").Any(c=>c.Status!="Failed"))throw new InvalidOperationException("Unexpected command outcomes");
if(registry.GetCommandHistory("pz-restart-integration-test").Single().Status!="Acknowledged")throw new InvalidOperationException("Enabled restart not acknowledged");
var output=registry.GetOutput(host.Id);
if(output.Count!=3 || !output.Select(x=>x.Stream).Order().SequenceEqual(new[]{"log","stderr","stdout"}))throw new InvalidOperationException("Output batch not accepted");
Console.WriteLine("PASS actual ServerPanel API: session heartbeat, output streams, stop/restart ACK and disabled-action refusal");
var oldSession=host.SessionId;
var oldCommand=registry.EnqueueCommand(host.Id,"stop");
var oldLease=registry.LeaseCommands(host.Id,sessionId:oldSession).Single();
await RunJava("io.serverpanelbridge.PanelSessionProbe",oldSession);
if(registry.GetHost(host.Id)!.SessionId==oldSession || registry.GetCommandHistory(host.Id).Single(c=>c.Id==oldCommand.Id).Status!="Cancelled")throw new InvalidOperationException("Session transition did not cancel old command");
using var http=new HttpClient{BaseAddress=new Uri(url)};
using var oldPoll=await http.GetAsync($"/api/hosts/{host.Id}/commands?sessionId={oldSession}");
using var oldAck=await http.PostAsJsonAsync($"/api/hosts/{host.Id}/commands/{oldCommand.Id}/ack",new CommandAcknowledgement(oldLease.LeaseToken!,true,"old session fixture",oldSession));
using var oldOutput=await http.PostAsJsonAsync($"/api/hosts/{host.Id}/output",new OutputBatchRequest(new[]{new OutputLineRequest("stale fixture")},oldSession));
if(oldPoll.StatusCode!=HttpStatusCode.Conflict || oldAck.StatusCode!=HttpStatusCode.Conflict || oldOutput.StatusCode!=HttpStatusCode.Conflict)throw new InvalidOperationException("Old session was not refused");
Console.WriteLine("PASS actual ServerPanel API: new JVM session cancels old stop; stale poll/ACK/output rejected (409)");
Console.WriteLine("Temporary loopback listener, memory-only registry, simulated game actions; active panel and game untouched.");
