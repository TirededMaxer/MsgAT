namespace MsgAT.Windows.Core.Storage;

public sealed class MediaStore
{
    public const long MaximumFileSize = 100L * 1024 * 1024;
    public const int ChunkSize = 45_000;

    public MediaStore(string dataDirectory)
    {
        RootDirectory = Path.Combine(dataDirectory, "media");
        Directory.CreateDirectory(RootDirectory);
    }

    public string RootDirectory { get; }

    public string GetPath(string messageId, string extension)
    {
        var safeId = Guid.TryParse(messageId, out var id) ? id.ToString() :
            throw new ArgumentException("Media message ID must be a UUID.", nameof(messageId));
        var safeExtension = NormalizeExtension(extension);
        return Path.Combine(RootDirectory, $"{safeId}.{safeExtension}");
    }

    public string Save(string messageId, string extension, ReadOnlySpan<byte> bytes)
    {
        if (bytes.Length > MaximumFileSize)
        {
            throw new InvalidDataException("Media exceeds the 100 MB limit.");
        }
        var path = GetPath(messageId, extension);
        var temporaryPath = path + ".tmp";
        try
        {
            using (var stream = new FileStream(temporaryPath, FileMode.Create, FileAccess.Write, FileShare.None))
            {
                stream.Write(bytes);
                stream.Flush(flushToDisk: true);
            }
            File.Move(temporaryPath, path, overwrite: true);
            return path;
        }
        finally
        {
            File.Delete(temporaryPath);
        }
    }

    public string SaveAvatar(string uuid, ReadOnlySpan<byte> bytes)
    {
        if (uuid.Length != 64 || uuid.Any(static character => !Uri.IsHexDigit(character)))
        {
            throw new ArgumentException("Avatar owner UUID is invalid.", nameof(uuid));
        }
        var path = Path.Combine(RootDirectory, $"avatar_{uuid.ToLowerInvariant()}.jpg");
        File.WriteAllBytes(path, bytes.ToArray());
        return path;
    }

    public string Import(string messageId, string sourcePath)
    {
        var info = new FileInfo(sourcePath);
        if (!info.Exists) { throw new FileNotFoundException("Media file does not exist.", sourcePath); }
        if (string.Equals(info.Extension, ".txt", StringComparison.OrdinalIgnoreCase))
        {
            throw new InvalidDataException("Text files cannot be sent as attachments. Copy their text into the message composer or send a ZIP file.");
        }
        if (info.Length > MaximumFileSize) { throw new InvalidDataException("Media exceeds the 100 MB limit."); }
        return Save(messageId, info.Extension, File.ReadAllBytes(sourcePath));
    }

    public static IEnumerable<string> ReadBase64Chunks(string sourcePath)
    {
        var info = new FileInfo(sourcePath);
        if (!info.Exists) { throw new FileNotFoundException("Media file does not exist.", sourcePath); }
        if (info.Length > MaximumFileSize) { throw new InvalidDataException("Media exceeds the 100 MB limit."); }
        if (info.Length == 0) { yield return ""; yield break; }
        using var stream = info.OpenRead();
        var buffer = new byte[ChunkSize];
        while (true)
        {
            var count = stream.ReadAtLeast(buffer, buffer.Length, throwOnEndOfStream: false);
            if (count == 0) { yield break; }
            yield return Convert.ToBase64String(buffer, 0, count);
        }
    }

    public void Delete(string messageId, string extension) => File.Delete(GetPath(messageId, extension));

    public void DeleteAll()
    {
        if (Directory.Exists(RootDirectory))
        {
            Directory.Delete(RootDirectory, recursive: true);
        }
        Directory.CreateDirectory(RootDirectory);
    }

    public static string NormalizeExtension(string extension)
    {
        var value = extension.Trim().TrimStart('.').ToLowerInvariant();
        if (value.Length is < 1 or > 10 || value.Any(static character => !char.IsAsciiLetterOrDigit(character)))
        {
            throw new ArgumentException("Media extension is invalid.", nameof(extension));
        }
        return value;
    }
}
