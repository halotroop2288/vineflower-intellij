package org.vineflower.ijplugin

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.ContextHelpLabel
import com.intellij.ui.IdeBorderFactory
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.util.text.SemVer
import com.intellij.util.ui.AsyncProcessIcon
import java.awt.FlowLayout
import java.util.TreeMap
import java.util.TreeSet
import javax.swing.BoxLayout
import javax.swing.ComboBoxModel
import javax.swing.JCheckBox
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.event.ListDataEvent
import javax.swing.event.ListDataListener

class VineflowerSettingsPanel(var prevVineflowerVersion: SemVer?) {
    companion object {
        private val LOGGER = logger<VineflowerSettingsPanel>()
    }

    lateinit var mainPanel: JPanel
    lateinit var pluginSettingsPanel: JPanel
    lateinit var vineflowerSettingsPanel: JPanel
    lateinit var enableVineflowerCheckbox: JBCheckBox
    lateinit var autoUpdateCheckbox: JBCheckBox
    lateinit var enableSnapshotsCheckbox: JBCheckBox
    lateinit var vineflowerVersionComboBox: ComboBox<SemVer>
    lateinit var fetchingVersionsIcon: AsyncProcessIcon
    lateinit var errorLabel: JLabel
    val vineflowerSettings = mutableMapOf<String, String>()

    init {
        pluginSettingsPanel.border = IdeBorderFactory.createTitledBorder("Plugin Settings")
        vineflowerSettingsPanel.layout = BoxLayout(vineflowerSettingsPanel, BoxLayout.Y_AXIS)

        refreshVineflowerSettings()

        autoUpdateCheckbox.addActionListener {
            vineflowerVersionComboBox.isEnabled = !autoUpdateCheckbox.isSelected
        }

        vineflowerVersionComboBox.addActionListener {
            refreshVineflowerSettings()
        }
        (vineflowerVersionComboBox.model as VineflowerVersionsModel).initialize()
    }

    fun createUIComponents() {
        vineflowerVersionComboBox = ComboBox(VineflowerVersionsModel())
        fetchingVersionsIcon = AsyncProcessIcon("Fetching Versions")
    }

    fun refreshVineflowerSettings() {
        vineflowerSettingsPanel.removeAll()
        if (vineflowerVersionComboBox.selectedItem != prevVineflowerVersion) {
            vineflowerSettingsPanel.add(JBLabel("Apply settings for Vineflower to be downloaded and settings to be displayed"))
            vineflowerSettingsPanel.revalidate()
            vineflowerSettingsPanel.repaint()
            return
        }

        val preferences = VineflowerPreferences.create()
        val allSettings = mutableListOf<VineflowerPreferences.SettingsEntry>()
        preferences.setupSettings(allSettings, vineflowerSettings)

        val groups = allSettings.groupByTo(TreeMap(compareBy { it })) { it.group }

        var groupPanel: JPanel
        for ((group, entries) in groups) {
            groupPanel = JPanel(FlowLayout(FlowLayout.LEFT))
            groupPanel.border = IdeBorderFactory.createTitledBorder(group ?: "Vineflower Settings")
            groupPanel.layout = BoxLayout(groupPanel, BoxLayout.Y_AXIS)

            for (entry in entries) {
                val panel = JPanel(FlowLayout(FlowLayout.LEFT))
                if (entry.component is JCheckBox) {
                    panel.add(entry.component)
                }
                panel.add(JBLabel(entry.name))
                if (entry.description != null) {
                    panel.add(ContextHelpLabel.create(entry.description))
                }
                if (entry.component !is JCheckBox) {
                    panel.add(entry.component)
                }
                groupPanel.add(panel)
            }

            vineflowerSettingsPanel.add(groupPanel)
        }
        vineflowerSettingsPanel.revalidate()
        vineflowerSettingsPanel.repaint()
    }

    inner class VineflowerVersionsModel : ComboBoxModel<SemVer> {
        private val versions = mutableListOf<SemVer>()
        private val listeners = mutableListOf<ListDataListener>()
        private var selectedItem: SemVer? = null

        private fun refreshVersions() {
            val (latestRelease, latestSnapshot, newReleases, newSnapshots, legacyReleases, legacySnapshots) =
                VineflowerState.getInstance().vineflowerVersionsFuture.getNow(null) ?: return
            val prevSize = versions.size
            versions.clear()
            versions.addAll(newReleases + legacyReleases)
            if (enableSnapshotsCheckbox.isSelected) {
                versions.addAll(newSnapshots + legacySnapshots)
            }
            val prevSelectedItem = selectedItem
            if (prevSelectedItem != null && prevSelectedItem !in versions) {
                versions += prevSelectedItem
            }
            versions.sortDescending()
            for (listener in listeners) {
                if (prevSize > versions.size) {
                    listener.intervalRemoved(ListDataEvent(this, ListDataEvent.INTERVAL_REMOVED, versions.size, prevSize - 1))
                }
                listener.contentsChanged(ListDataEvent(this, ListDataEvent.CONTENTS_CHANGED, 0, versions.size - 1))
                if (prevSize < versions.size) {
                    listener.intervalAdded(ListDataEvent(this, ListDataEvent.INTERVAL_ADDED, prevSize, versions.size - 1))
                }
            }
            when {
                prevSelectedItem != null -> {
                    vineflowerVersionComboBox.selectedItem = prevSelectedItem
                }
                autoUpdateCheckbox.isSelected -> {
                    vineflowerVersionComboBox.selectedItem = if (enableSnapshotsCheckbox.isSelected) latestSnapshot else latestRelease
                }
                else -> {
                    vineflowerVersionComboBox.selectedItem = VineflowerState.getInstance().vineflowerVersion
                }
            }
        }

        fun initialize() {
            if (!fetchingVersionsIcon.isRunning) {
                fetchingVersionsIcon.resume()
            }
            VineflowerState.getInstance().vineflowerVersionsFuture = VineflowerState.getInstance().downloadVineflowerVersions().whenComplete { _, error ->
                ApplicationManager.getApplication().invokeLater {
                    if (!fetchingVersionsIcon.isDisposed) {
                        fetchingVersionsIcon.suspend()
                        fetchingVersionsIcon.isVisible = false
                    }
                    if (error != null) {
                        errorLabel.text = "Error fetching versions"
                        LOGGER.error("Error fetching versions", error)
                        pluginSettingsPanel.revalidate()
                        pluginSettingsPanel.repaint()
                    } else {
                        refreshVersions()
                    }
                }
            }

            enableSnapshotsCheckbox.addActionListener {
                refreshVersions()
            }
        }

        override fun getSize() = versions.size

        override fun getElementAt(index: Int) = versions[index]

        override fun addListDataListener(l: ListDataListener) {
            listeners.add(l)
        }

        override fun removeListDataListener(l: ListDataListener) {
            listeners.remove(l)
        }

        override fun setSelectedItem(anItem: Any?) {
            selectedItem = anItem as? SemVer
        }

        override fun getSelectedItem(): Any? {
            return selectedItem
        }

    }
}
