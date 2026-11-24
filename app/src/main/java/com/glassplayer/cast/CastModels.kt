package com.glassplayer.cast

/** 局域网里发现的一台 DLNA 渲染设备（电视 / 盒子 / 音箱）。 */
data class CastDevice(
    /** 设备显示名，优先取描述文档里的 friendlyName。 */
    val name: String,
    /** 设备描述文档地址，用于回查控制地址。 */
    val location: String,
    /** AVTransport 控制地址，投屏指令发到这里。 */
    val avControlUrl: String,
    /** RenderingControl 控制地址，用来调音量；部分设备没有。 */
    val rcControlUrl: String? = null,
    /** 设备唯一标识，用于去重。 */
    val uuid: String = "",
    /** 描述文档里的型号，列表里做副标题。 */
    val model: String = "",
    /** 设备描述里声明的 AVTransport 服务类型；默认标准值，非标准设备会覆盖。 */
    val avServiceType: String = "urn:schemas-upnp-org:service:AVTransport:1",
    /** RenderingControl 服务类型，调音量用。 */
    val rcServiceType: String = "urn:schemas-upnp-org:service:RenderingControl:1",
) {
    val canControlVolume: Boolean get() = rcControlUrl != null
}

/** DLNA 播放状态。电视端是异步的，所以这里以轮询结果为准。 */
data class CastState(
    val device: CastDevice? = null,
    val connecting: Boolean = false,
    val casting: Boolean = false,
    val playing: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val title: String = "",
    val message: String? = null,
) {
    val active: Boolean get() = casting || connecting
}
