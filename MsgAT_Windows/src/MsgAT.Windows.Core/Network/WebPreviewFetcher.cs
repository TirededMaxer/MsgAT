// SPDX-License-Identifier: AGPL-3.0-only
using System.Net;
using System.Net.Sockets;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json.Nodes;
using MsgAT.Windows.Core.Crypto;

namespace MsgAT.Windows.Core.Network;

/// <summary>Ephemeral composer data. Only the messaging transaction may persist its encrypted representation.</summary>
public sealed record WebPreviewDraft(string Url,string Title,string Summary,string Site,byte[]? Image,bool AboveText=false,bool CompactImage=false)
{
    public void ClearImage() {if(Image is { } image)CryptographicOperations.ZeroMemory(image);}
    internal JsonObject Body(JsonObject? image)
    {
        var body = new JsonObject {["url"]=Url,["title"]=Title,["summary"]=Summary,["site"]=Site,["image"]=image?.DeepClone()};
        if (AboveText) body["above_text"] = true;
        if (CompactImage && image is not null) body["compact_image"] = true;
        return body;
    }
}

public static class WebPreviewFetcher
{
    private static readonly SemaphoreSlim Slots=new(2);
    private sealed record Response(Uri Url,string Type,byte[] Bytes);
    private static JsonObject Core(JsonObject request)=>JsonNode.Parse(SecurityCore.Call(request).GetRawText())!.AsObject();
    public static string[] Candidates(string text) =>
        Core(new(){["operation"]="visible_message_urls",["text"]=text})["urls"]!.AsArray()
            .Select(node => node!.GetValue<string>()).ToArray();

    public static async Task<WebPreviewDraft?> FetchUrlAsync(string candidate,Func<byte[],byte[]> normalizeImage,CancellationToken token)
    {
        using var lifetime=CancellationTokenSource.CreateLinkedTokenSource(token);lifetime.CancelAfter(TimeSpan.FromSeconds(12));
        var entered=false;
        try
        {
            await Slots.WaitAsync(lifetime.Token).ConfigureAwait(false);entered=true;
            var page=await GetAsync(new Uri(candidate),1024*1024,lifetime.Token).ConfigureAwait(false);
            JsonObject metadata;
            try
            {
                if(page.Type is not ("text/html" or "application/xhtml+xml"))return null;
                metadata=Core(new(){["operation"]="inspect_web_page",["url"]=candidate,["html"]=Encoding.UTF8.GetString(page.Bytes)});
            }
            finally {CryptographicOperations.ZeroMemory(page.Bytes);}
            byte[]? image=null;
            try
            {
                var source=metadata["image_url"]?.GetValue<string>();
                if(!string.IsNullOrWhiteSpace(source) && Uri.TryCreate(page.Url,source,out var url))
                {
                    var downloaded=await GetAsync(url,2*1024*1024,lifetime.Token).ConfigureAwait(false);
                    try
                    {
                        if(downloaded.Type is "image/jpeg" or "image/png" or "image/webp")
                        {
                            image=normalizeImage(downloaded.Bytes);
                            if(image.Length is <1 or >512*1024){CryptographicOperations.ZeroMemory(image);image=null;}
                        }
                    }
                    finally {CryptographicOperations.ZeroMemory(downloaded.Bytes);}
                }
            }
            catch(OperationCanceledException) when(token.IsCancellationRequested){throw;}
            catch(Exception) { /* Metadata remains useful when a cover is absent or cannot be decoded. */ }
            if(token.IsCancellationRequested){if(image is not null)CryptographicOperations.ZeroMemory(image);token.ThrowIfCancellationRequested();}
            return new(candidate,metadata["title"]!.GetValue<string>(),metadata["summary"]!.GetValue<string>(),metadata["site"]!.GetValue<string>(),image);
        }
        catch(OperationCanceledException) when(token.IsCancellationRequested){throw;}
        catch(Exception){return null;} // Failed previews never prevent sending the original text.
        finally {if(entered)Slots.Release();}
    }
    private static async Task<Response> GetAsync(Uri initial,int maximum,CancellationToken token)
    {
        var current=initial;
        for(var hop=0;hop<=3;hop++)
        {
            token.ThrowIfCancellationRequested();
            var target=Core(new(){["operation"]="web_preview_target",["url"]=current.AbsoluteUri});
            var host=target["host"]!.GetValue<string>();var port=target["port"]!.GetValue<int>();
            var addresses=await Dns.GetHostAddressesAsync(host,token).ConfigureAwait(false);
            Core(new(){["operation"]="web_preview_policy",["url"]=current.AbsoluteUri,["addresses"]=new JsonArray(addresses.Select(a=>(JsonNode?)JsonValue.Create(a.ToString())).ToArray())});
            using var handler=new SocketsHttpHandler
            {
                AllowAutoRedirect=false,UseCookies=false,UseProxy=false,AutomaticDecompression=DecompressionMethods.None,
                ConnectTimeout=TimeSpan.FromSeconds(3),MaxResponseHeadersLength=32,
                ConnectCallback=async (context,ct)=>
                {
                    if(!string.Equals(context.DnsEndPoint.Host,host,StringComparison.OrdinalIgnoreCase) || context.DnsEndPoint.Port!=port)throw new IOException("Unexpected preview endpoint.");
                    foreach(var address in addresses.OrderBy(a=>a.AddressFamily==AddressFamily.InterNetwork?0:1))
                    {
                        var socket=new Socket(address.AddressFamily,SocketType.Stream,ProtocolType.Tcp){NoDelay=true};
                        try {await socket.ConnectAsync(new IPEndPoint(address,port),ct).ConfigureAwait(false);return new NetworkStream(socket,ownsSocket:true);}
                        catch {socket.Dispose();if(ct.IsCancellationRequested)throw;}
                    }
                    throw new IOException("Preview destination is unreachable.");
                }
            };
            using var client=new HttpClient(handler){Timeout=Timeout.InfiniteTimeSpan};
            using var request=new HttpRequestMessage(HttpMethod.Get,current){Version=HttpVersion.Version11,VersionPolicy=HttpVersionPolicy.RequestVersionExact};
            request.Headers.UserAgent.ParseAdd("MsgAT-LinkPreview/2.0");request.Headers.TryAddWithoutValidation("Accept-Encoding","identity");
            using var response=await client.SendAsync(request,HttpCompletionOption.ResponseHeadersRead,token).ConfigureAwait(false);
            var status=(int)response.StatusCode;
            if(status is 301 or 302 or 303 or 307 or 308)
            {
                if(hop==3 || response.Headers.Location is not { } location)throw new IOException("Preview redirect limit reached.");
                var next=new Uri(current,location);if(current.Scheme=="https" && next.Scheme!="https")throw new IOException("Preview TLS downgrade is forbidden.");
                current=next;continue;
            }
            response.EnsureSuccessStatusCode();
            if(response.Content.Headers.ContentEncoding.Any(e=>e!="identity") || response.Content.Headers.ContentLength>maximum)throw new IOException("Preview response exceeds its limits.");
            await using var input=await response.Content.ReadAsStreamAsync(token).ConfigureAwait(false);
            using var output=new MemoryStream();var buffer=new byte[16384];
            try
            {
                while(true)
                {
                    var count=await input.ReadAsync(buffer,token).ConfigureAwait(false);if(count==0)break;
                    if(output.Length+count>maximum)throw new IOException("Preview response exceeds its limits.");
                    output.Write(buffer,0,count);
                }
                return new(current,response.Content.Headers.ContentType?.MediaType?.ToLowerInvariant()??"",output.ToArray());
            }
            finally {CryptographicOperations.ZeroMemory(buffer);if(output.TryGetBuffer(out var plaintext))CryptographicOperations.ZeroMemory(plaintext.AsSpan());}
        }
        throw new IOException("Preview redirect limit reached.");
    }
}
