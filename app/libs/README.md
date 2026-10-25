# 小米设备通信SDK依赖

Android手环同步使用小米官方XMS Wearable SDK 1.4。SDK不是本项目编写或重新授权的GPL代码，本仓库及公开源码ZIP不重分发原始AAR。

请从[小米设备通信官方教程](https://iot.mi.com/vela/quickapp/zh/features/network/interconnect.html)取得官方开发示例，按其适用条款使用SDK。官方示例下载地址：

https://cdn.cnbj3-fusion.fds.api.mi-img.com/quickapp-vela/interconnect_dev_test_demo.zip

将示例中的`xms-wearable-lib_1.4_release.aar`放在本目录，再构建Android应用。已验证文件SHA-256：

`9c40fd1c5409bb948523d503af71e2978ae522c35636afe7d64f474c0f6bc195`

源码中的`implementation files('libs/xms-wearable-lib_1.4_release.aar')`引用该文件；原始SDK的权利归属与使用条款仍由小米提供。
