// SPDX-License-Identifier: MIT
// 移植改动：原实现把服务挂在 BilightApplication 上；作为库模块嵌入 PromX 后，
// 进程里只有一个 Application（PromX 的），所以改成显式的服务容器。
package app.bilight.android

import android.app.Application
import app.bilight.android.core.BiliHttp
import app.bilight.android.core.BiliRepository
import app.bilight.android.core.SessionVault
import app.bilight.android.downloads.DownloadStore

class BilightServices(application: Application) {
    val http by lazy { BiliHttp(SessionVault(application)) }
    val repository by lazy { BiliRepository(http) }
    val downloads by lazy { DownloadStore(application, repository) }

    companion object {
        @Volatile private var instance: BilightServices? = null
        fun of(application: Application): BilightServices =
            instance ?: synchronized(this) {
                instance ?: BilightServices(application).also { instance = it }
            }
    }
}
