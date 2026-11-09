package cz.xprees.comark

import com.intellij.openapi.util.IconLoader
import javax.swing.Icon

object ComarkIcons {
    @JvmField
    val FILE: Icon = IconLoader.getIcon("/icons/comark.svg", ComarkIcons::class.java)

    @JvmField
    val COMPONENT: Icon = IconLoader.getIcon("/icons/component.svg", ComarkIcons::class.java)
}
