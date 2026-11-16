# Third-party notices

## Xray-core

The Android Xray engine includes Xray-core v26.9.30, distributed under the Mozilla Public License 2.0. The upstream source is available at https://github.com/XTLS/Xray-core/tree/v26.9.30 and the license text at https://github.com/XTLS/Xray-core/blob/v26.9.30/LICENSE.

## libXray

The Android binding is based on libXray v26.9.30, distributed under the MIT License. Upstream source: https://github.com/XTLS/libXray/tree/v26.9.30. The Java binding classes are extracted from its official Android release AAR and loaded in the isolated `:xray` process so its Go runtime does not share a process with sing-box.
