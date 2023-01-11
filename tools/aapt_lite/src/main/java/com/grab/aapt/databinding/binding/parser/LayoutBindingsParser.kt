/*
 * Copyright 2021 Grabtaxi Holdings PTE LTE (GRAB)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.grab.aapt.databinding.binding.parser

import com.grab.aapt.databinding.binding.model.Binding
import com.grab.aapt.databinding.binding.model.BindingType
import com.grab.aapt.databinding.binding.model.LayoutBindingData
import com.grab.aapt.databinding.binding.store.DEPS
import com.grab.aapt.databinding.binding.store.LOCAL
import com.grab.aapt.databinding.binding.store.LayoutTypeStore
import com.grab.aapt.databinding.di.AaptScope
import com.grab.aapt.databinding.util.attributesNameValue
import com.grab.aapt.databinding.util.events
import com.grab.aapt.databinding.util.extractPrimitiveType
import com.grab.aapt.databinding.util.toLayoutBindingName
import com.squareup.javapoet.ClassName
import com.squareup.javapoet.TypeName
import dagger.Binds
import dagger.Module
import org.xmlpull.v1.XmlPullParser.START_TAG
import org.xmlpull.v1.XmlPullParserFactory
import java.io.File
import javax.inject.Inject
import javax.inject.Named

interface LayoutBindingsParser {
    fun parse(packageName: String, layoutFiles: List<File>): List<LayoutBindingData>
}

@Module
interface BindingsParserModule {
    @Binds
    fun DefaultLayoutBindingsParser.layoutBindingsParser(): LayoutBindingsParser
}

/**
 * Type to represent types that are imported by databinding expressions usually in below format
 * ```
 * <import type="com.grab.Type">
 * ```
 * @see [https://developer.android.com/topic/libraries/data-binding/expressions#imports_variables_and_includes]
 */
private typealias ImportedTypes = MutableMap<String /* type name */, TypeName>

@AaptScope
class DefaultLayoutBindingsParser
@Inject
constructor(
    @Named(LOCAL)
    private val localLayoutTypeStore: LayoutTypeStore,
    @Named(DEPS)
    private val depLayoutTypeStore: LayoutTypeStore
) : LayoutBindingsParser {

    companion object {
        private const val TYPE = "type"
        private const val ALIAS = "alias"
        private const val NAME = "name"
        private const val VARIABLE = "variable"
        private const val ANDROID_ID = "android:id"
        private const val LAYOUT = "layout"
        private const val IMPORT = "import"
        internal const val INCLUDE = "include"
    }

    private val xpp = XmlPullParserFactory.newInstance().newPullParser()

    private val File.bindingName
        get() = name.split(".xml").first().toLayoutBindingName()

    override fun parse(
        packageName: String,
        layoutFiles: List<File>
    ): List<LayoutBindingData> {
        return layoutFiles.map { layoutFile ->
            layoutFile.inputStream().buffered().use { stream ->
                xpp.setInput(stream, null)
                val bindingClassName = layoutFile.bindingName
                val bindings = mutableSetOf<Binding>()
                val bindables = mutableSetOf<Binding>()

                val importedTypes: ImportedTypes = mutableMapOf()

                xpp.events()
                    .asSequence()
                    .forEach { event: Int ->
                        if (event == START_TAG) {
                            when (val nodeName = xpp.name) {
                                IMPORT -> {
                                    val attributes = xpp.attributesNameValue()
                                        .withDefault { error("Could not parse: $it") }
                                    val typeFqcn = attributes.getValue(TYPE)
                                    val typeName = attributes[ALIAS] ?: typeFqcn.split(".").last()
                                    importedTypes[typeName] = ClassName.bestGuess(typeFqcn)
                                }
                                VARIABLE -> {
                                    val attributes = xpp.attributesNameValue()
                                        .withDefault { error("Could not parse: $it") }
                                    bindables.add(
                                        Binding(
                                            rawName = attributes.getValue(NAME),
                                            typeName = parseBindableTypeName(
                                                typeName = attributes.getValue(TYPE),
                                                importedTypes = importedTypes
                                            ),
                                            bindingType = BindingType.Variable
                                        )
                                    )
                                }
                                else -> {
                                    val attributes = xpp.attributesNameValue()
                                        .filterKeys { it == ANDROID_ID || it == LAYOUT }
                                        .withDefault {
                                            error("Could not parse: $it in $packageName:$layoutFile")
                                        }

                                    parseBinding(
                                        packageName,
                                        nodeName,
                                        attributes
                                    )?.let(bindings::add)
                                }
                            }
                        }
                    }
                LayoutBindingData(
                    bindingClassName,
                    layoutFile,
                    bindings.toList(),
                    bindables.toList()
                )
            }
        }.distinctBy(LayoutBindingData::layoutName)
    }

    private fun parseBindableTypeName(
        typeName: String,
        importedTypes: ImportedTypes
    ): TypeName = if (!typeName.contains(".") && importedTypes.containsKey(typeName)) {
        importedTypes[typeName] ?: error("Imported type $typeName not found")
    } else {
        typeName.extractPrimitiveType() ?: ClassName.bestGuess(typeName)
    }

    /**
     * Parses the [BindingType] of the given node represented by [nodeName].
     *
     * @param nodeName The name of the XML node to process
     * @param attributes The XML attributes of the node.
     *
     * @return The parsed [BindingType]
     */
    fun parseBindingType(
        nodeName: String,
        attributes: Map<String, String>,
        layoutMissing: Boolean = false
    ): BindingType {
        if (!attributes.containsKey(ANDROID_ID)) error("Missing $ANDROID_ID")

        return when (nodeName) {
            INCLUDE -> {
                if (!attributes.containsKey(LAYOUT)) error("Missing @layout")

                BindingType.IncludedLayout(
                    layoutName = attributes
                        .getValue(LAYOUT)
                        .split("@layout/")
                        .last()
                        .toLayoutBindingName(),
                    layoutMissing = layoutMissing
                )
            }
            else -> BindingType.View
        }
    }

    private fun parseBinding(
        packageName: String,
        nodeName: String,
        attributes: Map<String, String>
    ): Binding? =
        if (attributes.containsKey(ANDROID_ID) && attributes.getValue(ANDROID_ID).contains("+")) {
            val idValue = attributes.getValue(ANDROID_ID)
            val parsedId = idValue.split("@+id/").last()

            // Flag to note if included layout type can't be found in either local or deps
            var layoutMissing = false

            val type: TypeName = when {
                nodeName.contains(".") -> ClassName.bestGuess(nodeName)
                nodeName == INCLUDE -> {
                    val (parsedType, missing) = parseIncludeTag(attributes, packageName)
                    layoutMissing = missing
                    parsedType
                }
                else -> when (nodeName) {
                    "ViewStub" -> ClassName.bestGuess("androidx.databinding.ViewStubProxy")
                    // https://android.googlesource.com/platform/frameworks/data-binding/+/refs/tags/studio-4.1.1/compilerCommon/src/main/java/android/databinding/tool/store/ResourceBundle.java#70
                    "View", "ViewGroup", "TextureView", "SurfaceView" -> {
                        ClassName.get("android.view", nodeName)
                    }
                    "WebView" -> ClassName.get("android.webkit", nodeName)
                    else -> ClassName.get("android.widget", nodeName)
                }
            }
            Binding(
                rawName = parsedId,
                typeName = type,
                bindingType = parseBindingType(nodeName, attributes, layoutMissing)
            )
        } else null

    /**
     * Infers the generated layout type from either current module layout files or ones present in
     * direct dependencies
     */
    fun parseIncludedLayoutType(layoutName: String): TypeName? {
        return localLayoutTypeStore[layoutName]
            ?: depLayoutTypeStore[layoutName]
    }

    /**
     * Try to extract the [TypeName] of layout from given <include> node's [attributes].
     *
     * @return Pair<TypeName, Boolean> where [TypeName] is the result of parsing. [Boolean] denotes
     *         layout was missing and could not parsed.
     */
    private fun parseIncludeTag(
        attributes: Map<String, String>,
        packageName: String
    ): Pair<TypeName, Boolean> {
        val layoutName = attributes
            .getValue(LAYOUT)
            .split("@layout/")
            .last()
        val parsedType = parseIncludedLayoutType(layoutName)
        return when {
            parsedType != null -> parsedType to false
            else -> {
                // Fallback to dummy binding in local module instead of failing the build.
                ClassName.get(
                    "$packageName.databinding",
                    layoutName.toLayoutBindingName()
                ) to true
            }
        }
    }
}