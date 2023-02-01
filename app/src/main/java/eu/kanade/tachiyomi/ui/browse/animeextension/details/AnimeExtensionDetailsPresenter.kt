package eu.kanade.tachiyomi.ui.browse.animeextension.details

import android.app.Application
import android.os.Bundle
import eu.kanade.domain.animeextension.interactor.GetAnimeExtensionSources
import eu.kanade.domain.animesource.interactor.ToggleAnimeSource
import eu.kanade.presentation.animebrowse.AnimeExtensionDetailsState
import eu.kanade.presentation.animebrowse.AnimeExtensionDetailsStateImpl
import eu.kanade.tachiyomi.animeextension.AnimeExtensionManager
import eu.kanade.tachiyomi.animesource.AnimeSource
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.ui.base.presenter.BasePresenter
import eu.kanade.tachiyomi.util.lang.launchIO
import eu.kanade.tachiyomi.util.lang.withUIContext
import eu.kanade.tachiyomi.util.system.LocaleHelper
import eu.kanade.tachiyomi.util.system.logcat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.map
import okhttp3.HttpUrl.Companion.toHttpUrl
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class AnimeExtensionDetailsPresenter(
    private val pkgName: String,
    private val state: AnimeExtensionDetailsStateImpl = AnimeExtensionDetailsState() as AnimeExtensionDetailsStateImpl,
    private val context: Application = Injekt.get(),
    private val getExtensionSources: GetAnimeExtensionSources = Injekt.get(),
    private val toggleSource: ToggleAnimeSource = Injekt.get(),
    private val network: NetworkHelper = Injekt.get(),
    private val extensionManager: AnimeExtensionManager = Injekt.get(),
) : BasePresenter<AnimeExtensionDetailsController>(), AnimeExtensionDetailsState by state {

    override fun onCreate(savedState: Bundle?) {
        super.onCreate(savedState)

        presenterScope.launchIO {
            extensionManager.installedExtensionsFlow
                .map { it.firstOrNull { pkg -> pkg.pkgName == pkgName } }
                .collectLatest { extension ->
                    // If extension is null it's most likely uninstalled
                    if (extension == null) {
                        withUIContext {
                            view?.onExtensionUninstalled()
                        }
                        return@collectLatest
                    }
                    state.extension = extension
                    fetchExtensionSources()
                }
        }
    }

    private fun CoroutineScope.fetchExtensionSources() {
        launchIO {
            getExtensionSources.subscribe(extension!!)
                .map {
                    it.sortedWith(
                        compareBy(
                            { item -> item.enabled.not() },
                            { item -> if (item.labelAsName) item.source.name else LocaleHelper.getSourceDisplayName(item.source.lang, context).lowercase() },
                        ),
                    )
                }
                .collectLatest {
                    state.isLoading = false
                    state.sources = it
                }
        }
    }

    fun getChangelogUrl(): String {
        extension ?: return ""

        val pkgName = extension.pkgName.substringAfter("eu.kanade.tachiyomi.animeextension.")
        val pkgFactory = extension.pkgFactory
        if (extension.hasChangelog) {
            return createUrl(URL_EXTENSION_BLOB, pkgName, pkgFactory, "/CHANGELOG.md")
        }

        // Falling back on GitHub commit history because there is no explicit changelog in extension
        return createUrl(URL_EXTENSION_COMMITS, pkgName, pkgFactory)
    }

    fun getReadmeUrl(): String {
        extension ?: return ""

        if (!extension.hasReadme) {
            return "https://aniyomi.org/help/faq/#extensions"
        }

        val pkgName = extension.pkgName.substringAfter("eu.kanade.tachiyomi.animeextension.")
        val pkgFactory = extension.pkgFactory
        return createUrl(URL_EXTENSION_BLOB, pkgName, pkgFactory, "/README.md")
    }

    fun clearCookies() {
        val urls = extension?.sources
            ?.filterIsInstance<AnimeHttpSource>()
            ?.map { it.baseUrl }
            ?.distinct() ?: emptyList()

        val cleared = urls.sumOf {
            network.cookieManager.remove(it.toHttpUrl())
        }

        logcat { "Cleared $cleared cookies for: ${urls.joinToString()}" }
    }

    fun uninstallExtension() {
        val extension = extension ?: return
        extensionManager.uninstallExtension(extension.pkgName)
    }

    fun toggleSource(sourceId: Long) {
        toggleSource.await(sourceId)
    }

    fun toggleSources(enable: Boolean) {
        extension?.sources
            ?.map { it.id }
            ?.let { toggleSource.await(it, enable) }
    }

    private fun createUrl(url: String, pkgName: String, pkgFactory: String?, path: String = ""): String {
        return if (!pkgFactory.isNullOrEmpty()) {
            when (path.isEmpty()) {
                true -> "$url/multisrc/src/main/java/eu/kanade/tachiyomi/multisrc/$pkgFactory"
                else -> "$url/multisrc/overrides/$pkgFactory/" + (pkgName.split(".").lastOrNull() ?: "") + path
            }
        } else {
            url + "/src/" + pkgName.replace(".", "/") + path
        }
    }
}

data class AnimeExtensionSourceItem(
    val source: AnimeSource,
    val enabled: Boolean,
    val labelAsName: Boolean,
)

private const val URL_EXTENSION_COMMITS = "https://github.com/jmir1/aniyomi-extensions/commits/master"
private const val URL_EXTENSION_BLOB = "https://github.com/jmir1/aniyomi-extensions/blob/master"
