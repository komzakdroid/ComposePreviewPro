package com.composepreviewpro.renderer

import org.mockito.Mockito
import org.mockito.stubbing.Answer
import java.io.StringReader
import java.lang.reflect.Proxy

/**
 * Fabricates a **placeholder vector drawable** for `painterResource(R.drawable.…)`
 * so Compose's Android resource path renders a valid (blank) icon off-device
 * instead of NPE-ing.
 *
 * The pipeline `painterResource` runs:
 *   1. `resources.getValue(id, TypedValue, true)` → reads `TypedValue.string`;
 *      if it ends in ".xml" Compose takes the VECTOR branch (the only viable
 *      one — the bitmap branch needs native image decoding).
 *   2. `resources.getXml(id)` → an `XmlResourceParser`; Compose seeks to the
 *      `<vector>` start tag and walks it.
 *   3. `resources.obtainAttributes(attrs, styleable)` → a `TypedArray`; Compose
 *      reads viewportWidth/Height/width/height from it, but ONLY for attributes
 *      the parser reports present (`getNamedFloat` calls
 *      `parser.getAttributeValue(android-ns, name)` first).
 *
 * So a minimal `<vector>` carrying exactly the four dimension attributes, plus a
 * TypedArray that returns 24f for getFloat/getDimension, yields a well-formed
 * 24×24 empty ImageVector — a clean placeholder, no crash. This handles ANY
 * drawable id uniformly (we don't read the real resources.arsc).
 */
internal object AndroidVectorStub {

    private const val ANDROID_NS = "http://schemas.android.com/apk/res/android"

    /** Minimal valid vector: the four dimension attrs, no path children. */
    private val PLACEHOLDER_VECTOR =
        """<vector xmlns:android="$ANDROID_NS" """ +
            """android:width="24dp" android:height="24dp" """ +
            """android:viewportWidth="24" android:viewportHeight="24"/>"""

    /**
     * Populate a caller-supplied `android.util.TypedValue` so that
     * `TypedValue.string` is a non-null ".xml" path (→ vector branch). Returns
     * true on success. Fields are public on TypedValue; we set them reflectively
     * to avoid a compile dependency on the Android SDK.
     */
    fun populateDrawableTypedValue(typedValue: Any, resId: Int): Boolean = try {
        val cls = typedValue.javaClass
        fun set(field: String, value: Any?) {
            runCatching { cls.getField(field).apply { isAccessible = true }.set(typedValue, value) }
        }
        // TYPE_STRING = 3.
        set("type", 3)
        set("string", "res/drawable/preview_$resId.xml")
        set("data", resId)
        set("resourceId", resId)
        set("assetCookie", 1)
        set("changingConfigurations", 0)
        set("density", 0)
        true
    } catch (_: Throwable) {
        false
    }

    /**
     * Build an `android.content.res.XmlResourceParser` over [PLACEHOLDER_VECTOR].
     * The XmlPullParser surface is delegated to a real parser (android-all bundles
     * org.xmlpull / kxml2); the extra AttributeSet/XmlResourceParser methods
     * return inert defaults (Compose reads attributes through obtainAttributes,
     * not through these).
     */
    fun createXmlResourceParser(classLoader: ClassLoader): Any? = try {
        val xmlResourceParserCls = classLoader.loadClass("android.content.res.XmlResourceParser")
        val xppCls = classLoader.loadClass("org.xmlpull.v1.XmlPullParser")
        val factoryCls = classLoader.loadClass("org.xmlpull.v1.XmlPullParserFactory")

        val factory = factoryCls.getMethod("newInstance").invoke(null)
        runCatching {
            factoryCls.getMethod("setNamespaceAware", java.lang.Boolean.TYPE).invoke(factory, true)
        }
        val realParser = factoryCls.getMethod("newPullParser").invoke(factory)
        xppCls.getMethod("setInput", java.io.Reader::class.java)
            .invoke(realParser, StringReader(PLACEHOLDER_VECTOR))

        Proxy.newProxyInstance(classLoader, arrayOf(xmlResourceParserCls)) { _, method, args ->
            val a = args ?: emptyArray()
            when (method.name) {
                "close" -> null
                // AttributeSet / XmlResourceParser-only extensions — inert.
                "getAttributeNameResource", "getAttributeResourceValue",
                "getAttributeUnsignedIntValue", "getAttributeIntValue",
                "getAttributeListValue", "getStyleAttribute",
                "getIdAttributeResourceValue" -> defaultForReturn(method.returnType)
                "getIdAttribute", "getClassAttribute" -> null
                else -> {
                    // Delegate everything else to the real XmlPullParser.
                    val target = xppCls.methods.firstOrNull {
                        it.name == method.name && it.parameterCount == a.size
                    }
                    if (target != null) target.invoke(realParser, *a)
                    else defaultForReturn(method.returnType)
                }
            }
        }
    } catch (t: Throwable) {
        System.err.println("[AndroidVectorStub] XmlResourceParser build failed: ${t.javaClass.simpleName}: ${t.message}")
        null
    }

    /**
     * A `TypedArray` for the vector styleable: returns 24f for float/dimension
     * reads (so the placeholder's viewport/size are 24×24) and inert defaults
     * elsewhere. Compose only consumes the entries whose attribute the parser
     * reported present, so over-broad 24f values for absent attrs are ignored.
     */
    fun createTypedArray(classLoader: ClassLoader): Any? = try {
        val typedArrayCls = classLoader.loadClass("android.content.res.TypedArray")
        val answer = Answer<Any?> { inv ->
            when (inv.method.name) {
                "getFloat", "getDimension" -> 24f
                "getDimensionPixelSize", "getDimensionPixelOffset", "getLayoutDimension" -> 24
                "hasValue", "getBoolean" -> false
                "getString", "getText", "getColorStateList", "getDrawable",
                "getNonResourceString" -> null
                "getChangingConfigurations", "getIndexCount", "length",
                "getInt", "getInteger", "getColor", "getResourceId", "getType",
                "getIndex" -> 0
                "recycle" -> null
                else -> Mockito.RETURNS_DEFAULTS.answer(inv)
            }
        }
        Mockito.mock(typedArrayCls, Mockito.withSettings().defaultAnswer(answer).stubOnly())
    } catch (t: Throwable) {
        System.err.println("[AndroidVectorStub] TypedArray build failed: ${t.javaClass.simpleName}: ${t.message}")
        null
    }

    /**
     * A stub `android.content.res.Resources$Theme`. Needed non-null because
     * `ImageVectorCache.Key.hashCode()` calls `theme.hashCode()`. When a theme
     * is present, Compose reads vector attributes via
     * `theme.obtainStyledAttributes(...)` (instead of `resources.obtainAttributes`),
     * so we route that to the same 24×24 placeholder [createTypedArray].
     */
    fun createTheme(classLoader: ClassLoader): Any? = try {
        val themeCls = classLoader.loadClass("android.content.res.Resources\$Theme")
        val answer = Answer<Any?> { inv ->
            when (inv.method.name) {
                "obtainStyledAttributes" -> createTypedArray(classLoader)
                else -> Mockito.RETURNS_DEFAULTS.answer(inv)
            }
        }
        Mockito.mock(themeCls, Mockito.withSettings().defaultAnswer(answer).stubOnly())
    } catch (t: Throwable) {
        System.err.println("[AndroidVectorStub] Theme build failed: ${t.message}")
        null
    }

    /**
     * A real `android.util.DisplayMetrics` with sane density (2.0 / 320dpi) so
     * the vector parser's `createVectorImageBuilder` (reads
     * `resources.getDisplayMetrics().density`) doesn't NPE. Constructed
     * reflectively; fields are public.
     */
    fun createDisplayMetrics(classLoader: ClassLoader): Any? = try {
        val cls = classLoader.loadClass("android.util.DisplayMetrics")
        val dm = cls.getDeclaredConstructor().newInstance()
        fun set(field: String, value: Any?) {
            runCatching { cls.getField(field).set(dm, value) }
        }
        set("density", 2.0f)
        set("scaledDensity", 2.0f)
        set("densityDpi", 320)
        set("widthPixels", 1080)
        set("heightPixels", 1920)
        set("xdpi", 320f)
        set("ydpi", 320f)
        dm
    } catch (t: Throwable) {
        System.err.println("[AndroidVectorStub] DisplayMetrics build failed: ${t.message}")
        null
    }

    private fun defaultForReturn(type: Class<*>): Any? = when (type) {
        java.lang.Boolean.TYPE -> false
        java.lang.Integer.TYPE -> 0
        java.lang.Long.TYPE -> 0L
        java.lang.Float.TYPE -> 0f
        java.lang.Double.TYPE -> 0.0
        java.lang.Short.TYPE -> 0.toShort()
        java.lang.Byte.TYPE -> 0.toByte()
        java.lang.Character.TYPE -> ' '
        java.lang.Void.TYPE -> null
        else -> null
    }
}
