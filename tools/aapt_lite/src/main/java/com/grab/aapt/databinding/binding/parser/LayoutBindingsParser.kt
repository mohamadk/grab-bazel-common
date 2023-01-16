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
import com.grab.aapt.databinding.util.capitalize
import com.grab.aapt.databinding.util.events
import com.grab.aapt.databinding.util.toLayoutBindingName
import com.squareup.javapoet.ArrayTypeName
import com.squareup.javapoet.ClassName
import com.squareup.javapoet.ParameterizedTypeName
import com.squareup.javapoet.TypeName
import dagger.Binds
import dagger.Module
import io.bazel.Logs
import org.xmlpull.v1.XmlPullParser.END_DOCUMENT
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
            Logs.logs.log("DefaultLayoutBindingsParser parse $layoutFile")
            layoutFile.inputStream().buffered(2 * 1024).use { stream ->
                Logs.logs.log("DefaultLayoutBindingsParser 2 parse $layoutFile")
                xpp.setInput(stream, null)
                val bindingClassName = layoutFile.bindingName
                val bindings = mutableSetOf<Binding>()
                val bindables = mutableSetOf<Binding>()

                val importedTypes: ImportedTypes = mutableMapOf()


//                    .asSequence()

//
                try {
                    while (true) {
                        Logs.logs.log("DefaultLayoutBindingsParser 3 parse $layoutFile")
                        val event = xpp.next()
                        Logs.logs.log("DefaultLayoutBindingsParser 4 parse $layoutFile")
                        if (event == END_DOCUMENT) {
                            Logs.logs.log("DefaultLayoutBindingsParser 5 parse $layoutFile")
                            break
                        }
                        build(event, importedTypes, bindables, packageName, layoutFile, bindings)
                        Logs.logs.log("DefaultLayoutBindingsParser 6 parse $layoutFile")
                    }
//
//                          xpp.events().asSequence().forEach { event: Int ->
//                            Logs.logs.log("DefaultLayoutBindingsParser 3 parse $layoutFile")
//                            build(event, importedTypes, bindables, packageName, layoutFile, bindings)
//                          Logs.logs.log("DefaultLayoutBindingsParser 4 parse $layoutFile")
//                            }.apply {
//                                Logs.logs.log("DefaultLayoutBindingsParser end $layoutFile")
//                            }
                } catch (e: Exception) {
                    Logs.logs.log("DefaultLayoutBindingsParser 7 parse $layoutFile")
                    Logs.logs.log(e)
                    throw e
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

    private fun build(
        event: Int,
        importedTypes: ImportedTypes,
        bindables: MutableSet<Binding>,
        packageName: String,
        layoutFile: File,
        bindings: MutableSet<Binding>
    ) {
        Logs.logs.log("DefaultLayoutBindingsParser 8 build $layoutFile")
        if (event == START_TAG) {
            Logs.logs.log("DefaultLayoutBindingsParser 9 build $layoutFile")
            when (val nodeName = xpp.name) {
                IMPORT -> {
                    Logs.logs.log("DefaultLayoutBindingsParser 10 build $layoutFile")
                    val attributes = xpp.attributesNameValue()
                        .withDefault { error("Could not parse: $it") }
                    Logs.logs.log("DefaultLayoutBindingsParser 10.1 build $layoutFile")
                    val typeFqcn = attributes.getValue(TYPE)
                    Logs.logs.log("DefaultLayoutBindingsParser 10.2 build $layoutFile")
                    val typeName = attributes[ALIAS] ?: typeFqcn.split(".").last()
                    Logs.logs.log("DefaultLayoutBindingsParser 11 build $layoutFile")
                    importedTypes[typeName] = ClassName.bestGuess(typeFqcn)
                }
                VARIABLE -> {
                    Logs.logs.log("DefaultLayoutBindingsParser 12 build $layoutFile")
                    val attributes = xpp.attributesNameValue()
                        .withDefault { error("Could not parse: $it") }

                    Logs.logs.log("DefaultLayoutBindingsParser 13 build $layoutFile")
                    val rawName = attributes.getValue(NAME)
                    Logs.logs.log("DefaultLayoutBindingsParser 13.1 build $layoutFile")
                    val typeName1 = attributes.getValue(TYPE)
                    Logs.logs.log("DefaultLayoutBindingsParser 13.1.1 build $layoutFile")
                    val typeName = typeName1.toTypeName(importedTypes = importedTypes,layoutFile.toString() )
                    Logs.logs.log("DefaultLayoutBindingsParser 13.2 build $layoutFile")
                    val bindingType = BindingType.Variable
                    Logs.logs.log("DefaultLayoutBindingsParser 13.3 build $layoutFile")

                    val a = rawName
                    Logs.logs.log("DefaultLayoutBindingsParser 13.4 build a=$a $layoutFile")
                    val b = a.split("_")
                    Logs.logs.log("DefaultLayoutBindingsParser 13.5 build b=$b $layoutFile")
                    val c = b.joinToString(
                        separator = "",
                        transform = String::capitalize
                    )
                    Logs.logs.log("DefaultLayoutBindingsParser 13.6 build c=$c $layoutFile")
                    val d = c.let { Character.toLowerCase(it.first()) + it.substring(1) }
                    Logs.logs.log("DefaultLayoutBindingsParser 13.7 build d=$d $layoutFile")


                    val bind = Binding(
                        rawName = rawName,
                        typeName = typeName,
                        bindingType = bindingType
                    )
                    Logs.logs.log("DefaultLayoutBindingsParser 13.8 build $layoutFile")
                    bindables.add(
                        bind
                    )
                    Logs.logs.log("DefaultLayoutBindingsParser 14 build $layoutFile")
                }
                else -> {
                    Logs.logs.log("DefaultLayoutBindingsParser 15 build $layoutFile")
                    val attributes = xpp.attributesNameValue()
                        .filterKeys { it == ANDROID_ID || it == LAYOUT }
                        .withDefault {
                            error("Could not parse: $it in $packageName:$layoutFile")
                        }
                    Logs.logs.log("DefaultLayoutBindingsParser 16 build $layoutFile")

                    parseBinding(
                        packageName,
                        nodeName,
                        attributes
                    )?.let(bindings::add)
                    Logs.logs.log("DefaultLayoutBindingsParser 17 build $layoutFile")
                }
            }
            Logs.logs.log("DefaultLayoutBindingsParser 18 build $layoutFile")
        }
        Logs.logs.log("DefaultLayoutBindingsParser 19 build $layoutFile")
    }

    /**
     * Parses the given type to a [TypeName].
     * Adapted from https://cs.android.com/androidx/platform/frameworks/data-binding/+/mirror-goog-studio-master-dev:compilerCommon/src/main/kotlin/android/databinding/tool/ext/ext.kt
     *
     * @param importedTypes A map of type names to [TypeName] imported in the binding layout.
     *
     * @return The parsed [TypeName]
     */
    private fun String.toTypeName(
        importedTypes: ImportedTypes,
        name:String
    ): TypeName {
        Logs.logs.log("String.toTypeName $name 1")
//        val genericEnd = this.lastIndexOf(">")
//        Logs.logs.log("String.toTypeName $name 2 genericEnd=$genericEnd ${Thread.currentThread().name}")
//        if (genericEnd >= 0) {
//            Logs.logs.log("String.toTypeName $name 3")
//            val genericStart = this.indexOf("<")
//            Logs.logs.log("String.toTypeName $name 4")
//            if (genericStart >= 0) {
//                Logs.logs.log("String.toTypeName $name 5")
//                val typeParams = this.substring(genericStart + 1, genericEnd).trim()
//                Logs.logs.log("String.toTypeName $name 6")
//                val typeParamsQualified = splitTemplateParameters(typeParams).map {
//                    Logs.logs.log("String.toTypeName $name 7")
//                    it.toTypeName(importedTypes,name)
//                }
//                Logs.logs.log("String.toTypeName $name 8")
//                val klass = this.substring(0, genericStart)
//                    .trim()
//                    .toTypeName(importedTypes,name)
//                Logs.logs.log("String.toTypeName $name 9")
//                return ParameterizedTypeName.get(klass as ClassName,
//                    *typeParamsQualified.toTypedArray()).also {
//                    Logs.logs.log("String.toTypeName $name 10")
//                }
//            }
//        }
//        Logs.logs.log("String.toTypeName $name 11 $this")
//        Logs.logs.log("String.toTypeName $name 11.1 $this $PRIMITIVE_TYPE_NAME_MAP")
//        val map=PRIMITIVE_TYPE_NAME_MAP[this]
//        Logs.logs.log("String.toTypeName $name 12 map=$map")
//        return (map ?: ClassName.bestGuess(this)).also {
//            Logs.logs.log("String.toTypeName $name 13 map=$map")
//        }



        if (this.endsWith("[]")) {
            Logs.logs.log("String.toTypeName $name 2 ${Thread.currentThread().name}")
            val qType = this.substring(0, this.length - 2)
                .trim()
                .toTypeName(importedTypes,name)
            Logs.logs.log("String.toTypeName $name 3  ${Thread.currentThread().name}")
            return ArrayTypeName.of(qType)
        }
        Logs.logs.log("String.toTypeName $name 4  ${Thread.currentThread().name}")
        val genericEnd = this.lastIndexOf(">")
        Logs.logs.log("String.toTypeName $name 5 genericEnd=$genericEnd ${Thread.currentThread().name}")
        if (genericEnd > 0) {
            Logs.logs.log("String.toTypeName $name 6  ${Thread.currentThread().name}")
            val genericStart = this.indexOf("<")
            Logs.logs.log("String.toTypeName $name 7  ${Thread.currentThread().name}")
            if (genericStart > 0) {
                Logs.logs.log("String.toTypeName $name 8  ${Thread.currentThread().name}")
                val typeParams = this.substring(genericStart + 1, genericEnd).trim()
                Logs.logs.log("String.toTypeName $name 9  ${Thread.currentThread().name}")
                val typeParamsQualified = splitTemplateParameters(typeParams).map {
                    Logs.logs.log("String.toTypeName $name 10  ${Thread.currentThread().name}")
                    it.toTypeName(importedTypes,name)
                }
                Logs.logs.log("String.toTypeName $name 11  ${Thread.currentThread().name}")
                val klass = this.substring(0, genericStart)
                    .trim()
                    .toTypeName(importedTypes,name)
                Logs.logs.log("String.toTypeName $name 12  ${Thread.currentThread().name}")
                return ParameterizedTypeName.get(
                    klass as ClassName,
                    *typeParamsQualified.toTypedArray()
                ).also {
                    Logs.logs.log("String.toTypeName $name 13  ${Thread.currentThread().name}")
                }
            }
        }
        Logs.logs.log("String.toTypeName $name 14  ${Thread.currentThread().name}")
        importedTypes[this]?.let { return it }
        Logs.logs.log("String.toTypeName $name 15  ${Thread.currentThread().name} PRIMITIVE_TYPE_NAME_MAP[this]=${PRIMITIVE_TYPE_NAME_MAP[this]}")
        return PRIMITIVE_TYPE_NAME_MAP[this] ?: ClassName.bestGuess(this).also {
            Logs.logs.log("String.toTypeName $name 16  ${Thread.currentThread().name}")
        }
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
    ): Binding? {
        Logs.logs.log("parseBinding $packageName 1 nodeName=$nodeName")
        return if (attributes.containsKey(ANDROID_ID) && attributes.getValue(ANDROID_ID).contains("+")) {
            Logs.logs.log("parseBinding $packageName 2 nodeName=$nodeName")
            val idValue = attributes.getValue(ANDROID_ID)
            Logs.logs.log("parseBinding $packageName 3 nodeName=$nodeName")
            val parsedId = idValue.split("@+id/").last()
            Logs.logs.log("parseBinding $packageName 4 nodeName=$nodeName")
            // Flag to note if included layout type can't be found in either local or deps
            var layoutMissing = false
            Logs.logs.log("parseBinding $packageName 5 nodeName=$nodeName")
            val type: TypeName = when {

                nodeName.contains(".") -> {
                    Logs.logs.log("parseBinding $packageName 6 nodeName=$nodeName")
                    ClassName.bestGuess(nodeName)
                }
                nodeName == INCLUDE -> {
                    Logs.logs.log("parseBinding $packageName 7 nodeName=$nodeName")
                    val (parsedType, missing) = parseIncludeTag(attributes, packageName)
                    layoutMissing = missing
                    Logs.logs.log("parseBinding $packageName 8 nodeName=$nodeName")
                    parsedType
                }
                else -> when (nodeName) {
                    "ViewStub" -> {
                        Logs.logs.log("parseBinding $packageName 10 nodeName=$nodeName")
                        ClassName.bestGuess("androidx.databinding.ViewStubProxy")
                    }
                    // https://android.googlesource.com/platform/frameworks/data-binding/+/refs/tags/studio-4.1.1/compilerCommon/src/main/java/android/databinding/tool/store/ResourceBundle.java#70
                    "View", "ViewGroup", "TextureView", "SurfaceView" -> {
                        Logs.logs.log("parseBinding $packageName 11 nodeName=$nodeName")
                        ClassName.get("android.view", nodeName)
                    }
                    "WebView" -> {
                        Logs.logs.log("parseBinding $packageName 12 nodeName=$nodeName")
                        ClassName.get("android.webkit", nodeName)}
                    else ->{
                        Logs.logs.log("parseBinding $packageName 13 nodeName=$nodeName")
                        ClassName.get("android.widget", nodeName)
                    }
                }
            }
            Logs.logs.log("parseBinding $packageName 14 nodeName=$nodeName")
            Binding(
                rawName = parsedId,
                typeName = type,
                bindingType = parseBindingType(nodeName, attributes, layoutMissing)
            ).also {
                Logs.logs.log("parseBinding $packageName 15 nodeName=$nodeName")
            }

        } else null
    }

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

    private fun splitTemplateParameters(templateParameters: String): ArrayList<String> {
        val list = ArrayList<String>()
        var index = 0
        var openCount = 0
        val arg = StringBuilder()
        while (index < templateParameters.length) {
            val c = templateParameters[index]
            if (c == ',' && openCount == 0) {
                list.add(arg.toString())
                arg.delete(0, arg.length)
            } else if (!Character.isWhitespace(c)) {
                arg.append(c)
                if (c == '<') {
                    openCount++
                } else if (c == '>') {
                    openCount--
                }
            }
            index++
        }
        list.add(arg.toString())
        return list
    }
}

private val PRIMITIVE_TYPE_NAME_MAP = mapOf(
    TypeName.VOID.toString() to TypeName.VOID,
    TypeName.BOOLEAN.toString() to TypeName.BOOLEAN,
    TypeName.BYTE.toString() to TypeName.BYTE,
    TypeName.SHORT.toString() to TypeName.SHORT,
    TypeName.INT.toString() to TypeName.INT,
    TypeName.LONG.toString() to TypeName.LONG,
    TypeName.CHAR.toString() to TypeName.CHAR,
    TypeName.FLOAT.toString() to TypeName.FLOAT,
    TypeName.DOUBLE.toString() to TypeName.DOUBLE
)
