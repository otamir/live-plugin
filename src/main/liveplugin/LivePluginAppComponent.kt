package liveplugin

import com.intellij.lang.LanguageUtil
import com.intellij.notification.NotificationGroupManager
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DataContext.EMPTY_CONTEXT
import com.intellij.openapi.actionSystem.Presentation
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.impl.NonProjectFileWritingAccessExtension
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.fileTypes.SyntaxHighlighter
import com.intellij.openapi.fileTypes.SyntaxHighlighterFactory
import com.intellij.openapi.fileTypes.SyntaxHighlighterProvider
import com.intellij.openapi.module.Module
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.Project.DIRECTORY_STORE_FOLDER
import com.intellij.openapi.util.NotNullLazyKey
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.LocalSearchScope
import com.intellij.psi.search.SearchScope
import com.intellij.psi.search.UseScopeEnlarger
import com.intellij.psi.util.PsiUtilCore
import com.intellij.usages.impl.rules.UsageType
import com.intellij.usages.impl.rules.UsageTypeProvider
import com.intellij.util.indexing.IndexableSetContributor
import liveplugin.IdeUtil.ideStartupActionPlace
import liveplugin.IdeUtil.invokeLaterOnEDT
import liveplugin.LivePluginPaths.livePluginsPath
import liveplugin.LivePluginPaths.livePluginsProjectDirName
import liveplugin.pluginrunner.RunPluginAction.Companion.runPlugins
import liveplugin.pluginrunner.groovy.GroovyPluginRunner
import liveplugin.pluginrunner.kotlin.KotlinPluginRunner
import java.io.IOException

class LivePluginAppComponent {
    companion object {
        const val livePluginId = "LivePlugin"
        val logger = Logger.getInstance(LivePluginAppComponent::class.java)
        // Lazy because it seems that it can be initialised before notification group is initilised in plugin.xml
        val livePluginNotificationGroup by lazy {
            NotificationGroupManager.getInstance().getNotificationGroup("Live Plugin")!!
        }

        fun pluginIdToPathMap(): Map<String, FilePath> {
            // TODO Use virtual file because the code below will access file system every time this function is called to update availability of actions
            return livePluginsPath
                .listFiles { file -> file.isDirectory && file.name != DIRECTORY_STORE_FOLDER }
                .associateBy { it.name }
        }

        fun isInvalidPluginFolder(virtualFile: VirtualFile): Boolean =
            virtualFile.toFilePath().findAll(GroovyPluginRunner.mainScript).isEmpty() &&
            virtualFile.toFilePath().findAll(KotlinPluginRunner.mainScript).isEmpty()

        fun VirtualFile.pluginFolder(): VirtualFile? {
            val parent = parent ?: return null
            return if (parent.toFilePath() == livePluginsPath || parent.name == livePluginsProjectDirName) this
            else parent.pluginFolder()
        }

        // TODO similar to VirtualFile.pluginFolder
        fun FilePath.findPluginFolder(): FilePath? {
            val parent = toFile().parent?.toFilePath() ?: return null
            return if (parent == livePluginsPath || parent.name == livePluginsProjectDirName) this
            else parent.findPluginFolder()
        }

        fun readSampleScriptFile(filePath: String): String =
            try {
                val inputStream = LivePluginAppComponent::class.java.classLoader.getResourceAsStream(filePath) ?: error("Couldn't find resource for '$filePath'.")
                FileUtil.loadTextAndClose(inputStream)
            } catch (e: IOException) {
                logger.error(e)
                ""
            }

        fun runAllPlugins() {
            invokeLaterOnEDT {
                val actionManager = ActionManager.getInstance()
                val event = AnActionEvent(
                    null,
                    EMPTY_CONTEXT,
                    ideStartupActionPlace,
                    Presentation(),
                    actionManager,
                    0
                )
                val pluginPaths = pluginIdToPathMap().keys.map { pluginIdToPathMap().getValue(it) }
                runPlugins(pluginPaths, event)
            }
        }
    }
}

class MakePluginFilesAlwaysEditable: NonProjectFileWritingAccessExtension {
    override fun isWritable(file: VirtualFile) = file.isUnderLivePluginsPath()
}

class EnableSyntaxHighlighterInLivePlugins: SyntaxHighlighterProvider {
    override fun create(fileType: FileType, project: Project?, file: VirtualFile?): SyntaxHighlighter? {
        if (project == null || file == null || !file.isUnderLivePluginsPath()) return null
        val language = LanguageUtil.getLanguageForPsi(project, file) ?: return null
        return SyntaxHighlighterFactory.getSyntaxHighlighter(language, project, file)
    }
}

class UsageTypeExtension: UsageTypeProvider {
    override fun getUsageType(element: PsiElement): UsageType? {
        val file = PsiUtilCore.getVirtualFile(element) ?: return null
        return if (!file.isUnderLivePluginsPath()) null
        else UsageType { "Usage in liveplugin" }
    }
}

class IndexSetContributor: IndexableSetContributor() {
    override fun getAdditionalRootsToIndex(): Set<VirtualFile> {
        return mutableSetOf(livePluginsPath.toVirtualFile() ?: return HashSet())
    }
}

class UseScopeExtension: UseScopeEnlarger() {
    override fun getAdditionalUseScope(element: PsiElement): SearchScope? {
        val useScope = element.useScope
        return if (useScope is LocalSearchScope) null else LivePluginsSearchScope.getScopeInstance(element.project)
    }

    private class LivePluginsSearchScope(project: Project): GlobalSearchScope(project) {
        override fun getDisplayName() = "LivePlugins"
        override fun contains(file: VirtualFile) = file.isUnderLivePluginsPath()
        override fun isSearchInModuleContent(aModule: Module) = false
        override fun isSearchInLibraries() = false

        companion object {
            private val SCOPE_KEY = NotNullLazyKey.create<LivePluginsSearchScope, Project>("LIVEPLUGIN_SEARCH_SCOPE_KEY") { project ->
                LivePluginsSearchScope(project)
            }

            fun getScopeInstance(project: Project): GlobalSearchScope = SCOPE_KEY.getValue(project)
        }
    }
}

private fun VirtualFile.isUnderLivePluginsPath() = FileUtil.startsWith(path, livePluginsPath.value)

