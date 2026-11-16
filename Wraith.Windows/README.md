# Wraith for Windows

Native WPF x64 desktop port. Release builds target .NET Framework 4.8 and ship the patched sing-box 1.14.1 core and official Wintun 0.14.1 DLL beside `Wraith.exe`.

## Run

Download and extract `release/Wraith-Windows-0.10.16-x64.zip`, then run `Wraith.exe`. Keep all three executables/libraries in the same folder. System Proxy mode runs as a normal user. TUN mode prompts for administrator permission. App data is kept under `%LOCALAPPDATA%\Wraith` and secrets are protected with Windows DPAPI.

## Build the desktop client

Install the .NET Framework 4.8 Developer Pack and MSBuild, and put `sing-box.exe` and `wintun.dll` under `../.tools/sing-box/`. Build `Wraith.Windows.csproj` using `Release|x64`.

## Rebuild the core

The source bundle contains the exact sing-box 1.14.1 source tarball, Wraith patch sources and upstream module lock files. Extract the source tarball, apply `third_party/wraith-core/apply.sh`, then build for Windows amd64 with Go 1.25.5, `CGO_ENABLED=0`, tags from `release/DEFAULT_BUILD_TAGS_WINDOWS`, and flags from `release/LDFLAGS`. Set the embedded version to `1.14.1-wraith`. The Wintun DLL is a separate signed upstream component; its license is included in the portable package and source archive.

The release archive is a portable build, not an installer. Connection behavior still needs device/server testing by the user.
