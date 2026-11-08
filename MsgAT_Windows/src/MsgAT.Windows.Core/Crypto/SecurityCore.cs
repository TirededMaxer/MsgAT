// SPDX-License-Identifier: AGPL-3.0-only
using System.Runtime.CompilerServices;
using System.Runtime.InteropServices;
using System.Runtime.Versioning;
using System.Security.Cryptography;
using System.Text.Json;

namespace MsgAT.Windows.Core.Crypto;

/// <summary>Bounded C ABI bridge. Rust allocations are always released by Rust.</summary>
[SupportedOSPlatform("windows")]
public static partial class SecurityCore
{
    private const string LibraryName = "msgat_security_core";

    [StructLayout(LayoutKind.Sequential)]
    private struct NativeBuffer
    {
        public nint Data;
        public nuint Length;
    }

    [LibraryImport(LibraryName, EntryPoint = "msgat_security_abi_version")]
    [UnmanagedCallConv(CallConvs = [typeof(CallConvCdecl)])]
    [DefaultDllImportSearchPaths(DllImportSearchPath.AssemblyDirectory | DllImportSearchPath.System32)]
    private static partial uint AbiVersion();

    [LibraryImport(LibraryName, EntryPoint = "msgat_security_call")]
    [UnmanagedCallConv(CallConvs = [typeof(CallConvCdecl)])]
    [DefaultDllImportSearchPaths(DllImportSearchPath.AssemblyDirectory | DllImportSearchPath.System32)]
    private static unsafe partial int Invoke(byte* input, nuint length, ref NativeBuffer output);

    [LibraryImport(LibraryName, EntryPoint = "msgat_buffer_free")]
    [UnmanagedCallConv(CallConvs = [typeof(CallConvCdecl)])]
    [DefaultDllImportSearchPaths(DllImportSearchPath.AssemblyDirectory | DllImportSearchPath.System32)]
    private static partial void Free(ref NativeBuffer buffer);

    public static unsafe JsonElement Call(object request)
    {
        ArgumentNullException.ThrowIfNull(request);
        if (AbiVersion() != 2) throw new InvalidOperationException("The native security component has an incompatible version.");
        byte[] encoded = JsonSerializer.SerializeToUtf8Bytes(request);
        NativeBuffer buffer = default;
        byte[]? responseBytes = null;
        try
        {
            if (encoded.Length is 0 or > 4 * 1024 * 1024) throw new InvalidDataException("The security request exceeds its size limit.");
            int status;
            fixed (byte* input = encoded) { status = Invoke(input, (nuint)encoded.Length, ref buffer); }
            if (status != 0 || buffer.Data == 0 || buffer.Length is 0 or > 8 * 1024 * 1024)
                throw new InvalidDataException("The native security component returned an invalid response.");
            responseBytes = new byte[checked((int)buffer.Length)];
            Marshal.Copy(buffer.Data, responseBytes, 0, responseBytes.Length);
            using var response = JsonDocument.Parse(responseBytes, new JsonDocumentOptions { MaxDepth = 32 });
            var root = response.RootElement;
            if (!root.TryGetProperty("ok", out var ok) || ok.ValueKind != JsonValueKind.True)
                throw new CryptographicException(root.TryGetProperty("error", out var error) && error.ValueKind == JsonValueKind.String
                    ? error.GetString() : "The security operation failed.");
            if (!root.TryGetProperty("result", out var result) || result.ValueKind != JsonValueKind.Object)
                throw new InvalidDataException("The native security component returned an invalid result.");
            return result.Clone();
        }
        finally
        {
            CryptographicOperations.ZeroMemory(encoded);
            if (responseBytes is not null) CryptographicOperations.ZeroMemory(responseBytes);
            Free(ref buffer);
        }
    }
}
