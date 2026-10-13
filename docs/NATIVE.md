# 原生依赖

NFS 客户端使用 [libnfs](https://github.com/sahlberg/libnfs) 8.0.0，固定提交为 `5b1bc60260b5ddac34900c5315010b88b73e7edd`。源码包 SHA256 为 `80b67a8905f7e2051245c6fadc1c4beb7a6481da278e7664fee146e3bbe4b0f1`，下载和校验由 CMake FetchContent 执行。

Android NDK 构建 `libnfs.so`，Whisp 的 `libwhisp_nfs.so` 通过 JNI 调用它的只读目录、文件属性和随机读取接口。协议与 RPC/XDR 由 libnfs 实现；连接、文件句柄和缓冲区随操作关闭。文件名以 UTF-8 字节在 JNI 两侧传递，文件长度和偏移使用 64 位数值。

构建支持 NFS 3、4.0、4.2、TCP 与 AUTH_SYS。每个连接复用文件访问所需的 RPC 上下文；扫描最多使用四个独立连接，媒体读取采用 256 KiB 缓冲。读取超时与重连次数有限，客户端使用只读挂载和只读文件句柄。

libnfs 的库实现采用 LGPL-2.1-or-later，协议定义及生成文件采用其上游提供的 BSD 许可；上游许可说明见 [COPYING](https://github.com/sahlberg/libnfs/blob/libnfs-8.0.0/COPYING)，每个源码文件保留相应声明。Whisp 的 JNI 桥接层采用项目的 LGPL-3.0-or-later 许可。

发布原生安装包时，配套源码应包含 Whisp 构建文件、JNI 桥接层及该固定提交的完整 libnfs 源码。可从校验过的源码包重建两个共享库，并通过 Android Gradle Plugin 重新打包和签名。

Release 的源码 ZIP 将完整第三方源码放在 `third_party/libnfs`。CMake 的 `FETCHCONTENT_SOURCE_DIR_LIBNFS` 参数可指定该目录，使用配套源码构建原生库；常规仓库构建按上述地址和校验值获取相同源码。

原生桥接代码通过 `clang-format-19` 格式检查与编译器的 `-Wall -Wextra -Werror` 检查。四种 ABI 均由相同源代码构建。
