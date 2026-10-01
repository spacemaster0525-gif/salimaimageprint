package com.salimaprint.app.render

import android.util.Base64
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * محوّل مبسّط من ملف Word (.docx) إلى HTML، يكفي للطباعة:
 * فقرات، خط عريض/مائل/تسطير، أحجام وألوان وخطوط، محاذاة، اتجاه RTL، مسافات وإزاحات،
 * قوائم (برصاصة)، جداول (مع الحدود ودمج الأعمدة)، وصور مضمّنة.
 * لا يدعم الرؤوس والتذييلات ولا الترقيم الفعلي ولا دمج الخلايا عمودياً.
 */
object DocxToHtml {

    class Result(val html: String, val marginsMils: IntArray)

    fun convert(input: InputStream): Result = Converter(input).run()

    // ------------------------------------------------------------------ props

    private class Props {
        var b: Boolean? = null; var bCs: Boolean? = null
        var i: Boolean? = null; var iCs: Boolean? = null
        var u: Boolean? = null; var strike: Boolean? = null
        var sz: Float? = null; var szCs: Float? = null
        var color: String? = null
        var fontLatin: String? = null; var fontCs: String? = null
        var rtl: Boolean? = null
        var vert: String? = null
        var styleId: String? = null
        var jc: String? = null; var bidi: Boolean? = null
        var before: Int? = null; var after: Int? = null
        var line: Int? = null; var lineRule: String? = null
        var indStart: Int? = null; var indEnd: Int? = null
        var first: Int? = null; var hanging: Int? = null
        var numbered: Boolean? = null
        var pageBreakBefore: Boolean? = null
    }

    private fun merge(a: Props, o: Props?): Props {
        if (o == null) return a
        val r = Props()
        r.b = o.b ?: a.b; r.bCs = o.bCs ?: a.bCs
        r.i = o.i ?: a.i; r.iCs = o.iCs ?: a.iCs
        r.u = o.u ?: a.u; r.strike = o.strike ?: a.strike
        r.sz = o.sz ?: a.sz; r.szCs = o.szCs ?: a.szCs
        r.color = o.color ?: a.color
        r.fontLatin = o.fontLatin ?: a.fontLatin; r.fontCs = o.fontCs ?: a.fontCs
        r.rtl = o.rtl ?: a.rtl; r.vert = o.vert ?: a.vert
        r.jc = o.jc ?: a.jc; r.bidi = o.bidi ?: a.bidi
        r.before = o.before ?: a.before; r.after = o.after ?: a.after
        r.line = o.line ?: a.line; r.lineRule = o.lineRule ?: a.lineRule
        r.indStart = o.indStart ?: a.indStart; r.indEnd = o.indEnd ?: a.indEnd
        r.first = o.first ?: a.first; r.hanging = o.hanging ?: a.hanging
        r.numbered = o.numbered ?: a.numbered
        r.pageBreakBefore = o.pageBreakBefore ?: a.pageBreakBefore
        return r
    }

    private class Style(val id: String, val type: String, val isDefault: Boolean) {
        var basedOn: String? = null
        val p = Props()
        val r = Props()
        var tableBorder = false
    }

    private class Para { val props = Props(); val buf = StringBuilder(); var runProps: Props? = null; var cx = 0L }

    private class Tbl {
        var border = false; var width: String? = null; var rtl = false; var opened = false
        var cellPending = false; var span = 1; var cellBorder = false
        var cellWidth: String? = null; var cellShade: String? = null
    }

    // -------------------------------------------------------------- converter

    private class Converter(input: InputStream) {
        private val files = HashMap<String, ByteArray>()
        private val rels = HashMap<String, String>()
        private val styles = HashMap<String, Style>()
        private var defaultParaStyle: String? = null
        private val defP = Props()
        private val defR = Props()
        private var mL = 1000; private var mT = 1000; private var mR = 1000; private var mB = 1000

        private val body = StringBuilder()
        private val paras = ArrayList<Para>()
        private val tbls = ArrayList<Tbl>()

        init {
            ZipInputStream(input).use { zin ->
                while (true) {
                    val e = zin.nextEntry ?: break
                    if (e.isDirectory) continue
                    val n = e.name
                    val wanted = n == "word/document.xml" || n == "word/styles.xml" ||
                        n == "word/_rels/document.xml.rels" || n.startsWith("word/media/")
                    if (!wanted) continue
                    val bytes = zin.readBytes()
                    if (n.startsWith("word/media/") && bytes.size > 8_000_000) continue
                    files[n] = bytes
                }
            }
        }

        fun run(): Result {
            val doc = files["word/document.xml"]
                ?: throw IllegalArgumentException("الملف ليس مستند Word (.docx) صالحاً")
            files["word/_rels/document.xml.rels"]?.let { parseRels(it) }
            files["word/styles.xml"]?.let { parseStyles(it) }
            parseDocument(doc)
            val html = "<!DOCTYPE html><html><head><meta charset=\"utf-8\"><style>" +
                "html,body{margin:0;padding:0;background:#fff;color:#000;}" +
                "body{font-family:'Times New Roman',serif;font-size:11pt;}" +
                ".p{white-space:pre-wrap;word-wrap:break-word;}" +
                "table{border-collapse:collapse;}td{vertical-align:top;padding:1pt 5pt;}" +
                "img{max-width:100%;height:auto;}" +
                "</style></head><body>" + body + "</body></html>"
            return Result(html, intArrayOf(mL, mT, mR, mB))
        }

        // ---------------------------------------------------------- xml utils

        private fun parser(bytes: ByteArray): XmlPullParser {
            val p = Xml.newPullParser()
            p.setInput(ByteArrayInputStream(bytes), "UTF-8")
            return p
        }

        private fun local(p: XmlPullParser): String = (p.name ?: "").substringAfter(':')

        private fun attr(p: XmlPullParser, name: String): String? {
            for (i in 0 until p.attributeCount) {
                val n = p.getAttributeName(i)
                if (n == name || n.endsWith(":$name")) return p.getAttributeValue(i)
            }
            return null
        }

        private fun on(p: XmlPullParser): Boolean {
            val v = attr(p, "val") ?: return true
            return !(v == "0" || v == "false" || v == "off" || v == "none")
        }

        private fun skip(p: XmlPullParser) {
            val d = p.depth
            while (true) {
                val ev = p.next()
                if (ev == XmlPullParser.END_DOCUMENT) return
                if (ev == XmlPullParser.END_TAG && p.depth == d) return
            }
        }

        // ---------------------------------------------------------- readers

        private fun readProps(p: XmlPullParser, container: String, out: Props) {
            val d = p.depth
            while (true) {
                val ev = p.next()
                if (ev == XmlPullParser.END_DOCUMENT) return
                if (ev == XmlPullParser.END_TAG && p.depth == d) return
                if (ev != XmlPullParser.START_TAG) continue
                when (local(p)) {
                    "rPrChange", "pPrChange", "sectPrChange", "numberingChange" -> skip(p)
                    "rPr" -> if (container == "pPr") skip(p)
                    "sectPr" -> readSect(p)
                    "pStyle", "rStyle" -> out.styleId = attr(p, "val")
                    "jc" -> out.jc = attr(p, "val")
                    "bidi" -> out.bidi = on(p)
                    "pageBreakBefore" -> out.pageBreakBefore = on(p)
                    "numId" -> if ((attr(p, "val") ?: "0") != "0") out.numbered = true
                    "spacing" -> {
                        attr(p, "before")?.toIntOrNull()?.let { out.before = it }
                        attr(p, "after")?.toIntOrNull()?.let { out.after = it }
                        attr(p, "line")?.toIntOrNull()?.let { out.line = it }
                        attr(p, "lineRule")?.let { out.lineRule = it }
                    }
                    "ind" -> {
                        (attr(p, "start") ?: attr(p, "left"))?.toIntOrNull()?.let { out.indStart = it }
                        (attr(p, "end") ?: attr(p, "right"))?.toIntOrNull()?.let { out.indEnd = it }
                        attr(p, "firstLine")?.toIntOrNull()?.let { out.first = it }
                        attr(p, "hanging")?.toIntOrNull()?.let { out.hanging = it }
                    }
                    "b" -> out.b = on(p)
                    "bCs" -> out.bCs = on(p)
                    "i" -> out.i = on(p)
                    "iCs" -> out.iCs = on(p)
                    "u" -> out.u = on(p)
                    "strike", "dstrike" -> out.strike = on(p)
                    "rtl" -> out.rtl = on(p)
                    "sz" -> attr(p, "val")?.toFloatOrNull()?.let { out.sz = it / 2f }
                    "szCs" -> attr(p, "val")?.toFloatOrNull()?.let { out.szCs = it / 2f }
                    "color" -> attr(p, "val")?.let { if (it != "auto") out.color = it }
                    "vertAlign" -> out.vert = attr(p, "val")
                    "rFonts" -> {
                        (attr(p, "ascii") ?: attr(p, "hAnsi"))?.let { out.fontLatin = it }
                        attr(p, "cs")?.let { out.fontCs = it }
                    }
                }
            }
        }

        private fun readSect(p: XmlPullParser) {
            val d = p.depth
            while (true) {
                val ev = p.next()
                if (ev == XmlPullParser.END_DOCUMENT) return
                if (ev == XmlPullParser.END_TAG && p.depth == d) return
                if (ev == XmlPullParser.START_TAG && local(p) == "pgMar") {
                    fun mil(n: String) = attr(p, n)?.toIntOrNull()?.let { (it * 1000L / 1440).toInt() }
                    mL = mil("left") ?: mL; mT = mil("top") ?: mT
                    mR = mil("right") ?: mR; mB = mil("bottom") ?: mB
                }
            }
        }

        /** يقرأ عنصر حدود (tblBorders/tcBorders): true إذا وُجد حد ظاهر. */
        private fun readBorders(p: XmlPullParser): Boolean {
            var visible = false
            val d = p.depth
            while (true) {
                val ev = p.next()
                if (ev == XmlPullParser.END_DOCUMENT) return visible
                if (ev == XmlPullParser.END_TAG && p.depth == d) return visible
                if (ev == XmlPullParser.START_TAG) {
                    when (local(p)) {
                        "top", "left", "bottom", "right", "start", "end", "insideH", "insideV" -> {
                            val v = attr(p, "val")
                            if (v != null && v != "nil" && v != "none") visible = true
                        }
                    }
                }
            }
        }

        private fun readTblPr(p: XmlPullParser, t: Tbl) {
            val d = p.depth
            while (true) {
                val ev = p.next()
                if (ev == XmlPullParser.END_DOCUMENT) return
                if (ev == XmlPullParser.END_TAG && p.depth == d) return
                if (ev != XmlPullParser.START_TAG) continue
                when (local(p)) {
                    "tblStyle" -> if (styles[attr(p, "val") ?: ""]?.tableBorder == true) t.border = true
                    "tblBorders" -> if (readBorders(p)) t.border = true
                    "bidiVisual" -> t.rtl = on(p)
                    "tblW" -> {
                        val w = attr(p, "w")?.toIntOrNull()
                        when (attr(p, "type")) {
                            "dxa" -> if (w != null && w > 0) t.width = "${w / 20f}pt"
                            "pct" -> if (w != null && w > 0) t.width = "${w / 50f}%"
                        }
                    }
                    "tblCellMar" -> skip(p)
                }
            }
        }

        private fun readTcPr(p: XmlPullParser, t: Tbl) {
            val d = p.depth
            while (true) {
                val ev = p.next()
                if (ev == XmlPullParser.END_DOCUMENT) return
                if (ev == XmlPullParser.END_TAG && p.depth == d) return
                if (ev != XmlPullParser.START_TAG) continue
                when (local(p)) {
                    "tcW" -> {
                        val w = attr(p, "w")?.toIntOrNull()
                        if (attr(p, "type") == "dxa" && w != null && w > 0) t.cellWidth = "${w / 20f}pt"
                    }
                    "gridSpan" -> attr(p, "val")?.toIntOrNull()?.let { if (it > 1) t.span = it }
                    "tcBorders" -> if (readBorders(p)) t.cellBorder = true
                    "shd" -> attr(p, "fill")?.let { if (it != "auto" && it.length == 6) t.cellShade = "#$it" }
                }
            }
        }

        private fun parseRels(bytes: ByteArray) {
            val p = parser(bytes)
            var ev = p.eventType
            while (ev != XmlPullParser.END_DOCUMENT) {
                if (ev == XmlPullParser.START_TAG && local(p) == "Relationship") {
                    val id = attr(p, "Id"); val target = attr(p, "Target")
                    if (id != null && target != null) rels[id] = target
                }
                ev = p.next()
            }
        }

        private fun parseStyles(bytes: ByteArray) {
            val p = parser(bytes)
            var ev = p.eventType
            var cur: Style? = null
            var inRDef = false; var inPDef = false
            while (ev != XmlPullParser.END_DOCUMENT) {
                if (ev == XmlPullParser.START_TAG) {
                    when (local(p)) {
                        "rPrDefault" -> inRDef = true
                        "pPrDefault" -> inPDef = true
                        "style" -> cur = Style(attr(p, "styleId") ?: "", attr(p, "type") ?: "paragraph",
                            attr(p, "default") == "1")
                        "basedOn" -> { val c = cur; if (c != null) c.basedOn = attr(p, "val") }
                        "tblStylePr" -> skip(p)
                        "tblBorders" -> { val v = readBorders(p); val c = cur; if (v && c != null) c.tableBorder = true }
                        "rPr" -> {
                            val c = cur
                            if (inRDef) readProps(p, "rPr", defR)
                            else if (c != null) readProps(p, "rPr", c.r)
                        }
                        "pPr" -> {
                            val c = cur
                            if (inPDef) readProps(p, "pPr", defP)
                            else if (c != null) readProps(p, "pPr", c.p)
                        }
                    }
                } else if (ev == XmlPullParser.END_TAG) {
                    when (local(p)) {
                        "rPrDefault" -> inRDef = false
                        "pPrDefault" -> inPDef = false
                        "style" -> {
                            val c = cur
                            if (c != null) {
                                styles[c.id] = c
                                if (c.type == "paragraph" && c.isDefault) defaultParaStyle = c.id
                            }
                            cur = null
                        }
                    }
                }
                ev = p.next()
            }
        }

        // ------------------------------------------------------ style lookup

        /** يعيد (خصائص الفقرة، خصائص الخط) بعد دمج سلسلة basedOn. */
        private fun resolve(id: String?): Pair<Props, Props> {
            var pp = Props(); var rr = Props()
            if (id == null) return Pair(pp, rr)
            val chain = ArrayList<Style>()
            var cur = styles[id]
            var guard = 0
            while (cur != null && guard++ < 12) {
                chain.add(0, cur)
                cur = cur.basedOn?.let { styles[it] }
            }
            for (s in chain) { pp = merge(pp, s.p); rr = merge(rr, s.r) }
            return Pair(pp, rr)
        }

        // --------------------------------------------------------- document

        private fun out(): StringBuilder = if (paras.isNotEmpty()) paras.last().buf else body

        private fun openTable(t: Tbl) {
            if (t.opened) return
            t.opened = true
            val sb = StringBuilder("<table style=\"")
            if (t.rtl) sb.append("direction:rtl;")
            sb.append(if (t.width != null) "width:${t.width};" else "")
            if (t.border) sb.append("border:1px solid #000;")
            sb.append("\">")
            out().append(sb)
        }

        private fun ensureCellOpen() {
            val t = tbls.lastOrNull() ?: return
            if (!t.cellPending) return
            t.cellPending = false
            val sb = StringBuilder("<td")
            if (t.span > 1) sb.append(" colspan=\"${t.span}\"")
            sb.append(" style=\"")
            if (t.cellWidth != null) sb.append("width:${t.cellWidth};")
            if (t.border || t.cellBorder) sb.append("border:1px solid #000;")
            if (t.cellShade != null) sb.append("background:${t.cellShade};")
            sb.append("\">")
            out().append(sb)
        }

        private fun parseDocument(bytes: ByteArray) {
            val p = parser(bytes)
            var ev = p.eventType
            while (ev != XmlPullParser.END_DOCUMENT) {
                if (ev == XmlPullParser.START_TAG) {
                    when (local(p)) {
                        "Fallback", "sdtPr", "sdtEndPr", "trPr", "tblPrEx", "tblGrid" -> skip(p)
                        "sectPr" -> readSect(p)
                        "tbl" -> { ensureCellOpen(); tbls.add(Tbl()) }
                        "tblPr" -> tbls.lastOrNull()?.let { readTblPr(p, it) }
                        "tr" -> tbls.lastOrNull()?.let { openTable(it); out().append("<tr>") }
                        "tc" -> tbls.lastOrNull()?.let {
                            it.cellPending = true; it.span = 1; it.cellBorder = false
                            it.cellWidth = null; it.cellShade = null
                        }
                        "tcPr" -> tbls.lastOrNull()?.let { readTcPr(p, it) }
                        "p" -> { ensureCellOpen(); paras.add(Para()) }
                        "pPr" -> paras.lastOrNull()?.let { readProps(p, "pPr", it.props) }
                        "r" -> paras.lastOrNull()?.runProps = Props()
                        "rPr" -> paras.lastOrNull()?.let {
                            val r = Props(); readProps(p, "rPr", r); it.runProps = r
                        }
                        "t" -> {
                            val text = p.nextText()
                            paras.lastOrNull()?.let { it.buf.append(runHtml(it, text)) }
                        }
                        "tab" -> paras.lastOrNull()?.buf?.append("\u2003\u2003")
                        "br" -> paras.lastOrNull()?.buf?.append(
                            if (attr(p, "type") == "page")
                                "<div style=\"break-after:page;page-break-after:always;height:0\"></div>"
                            else "<br>")
                        "cr" -> paras.lastOrNull()?.buf?.append("<br>")
                        "noBreakHyphen" -> paras.lastOrNull()?.buf?.append("-")
                        "extent" -> paras.lastOrNull()?.cx = attr(p, "cx")?.toLongOrNull() ?: 0L
                        "blip" -> paras.lastOrNull()?.let { imageTag(it, attr(p, "r:embed") ?: attr(p, "embed")) }
                        "imagedata" -> paras.lastOrNull()?.let { imageTag(it, attr(p, "r:id") ?: attr(p, "id")) }
                    }
                } else if (ev == XmlPullParser.END_TAG) {
                    when (local(p)) {
                        "p" -> if (paras.isNotEmpty()) {
                            val pa = paras.removeAt(paras.size - 1)
                            out().append(paraHtml(pa))
                        }
                        "tc" -> { ensureCellOpen(); if (tbls.isNotEmpty()) out().append("</td>") }
                        "tr" -> if (tbls.isNotEmpty()) out().append("</tr>")
                        "tbl" -> if (tbls.isNotEmpty()) {
                            val t = tbls.removeAt(tbls.size - 1)
                            if (t.opened) out().append("</table>")
                        }
                    }
                }
                ev = p.next()
            }
        }

        // ------------------------------------------------------------ output

        private fun esc(s: String): String {
            val sb = StringBuilder(s.length + 8)
            for (c in s) when (c) {
                '&' -> sb.append("&amp;"); '<' -> sb.append("&lt;"); '>' -> sb.append("&gt;")
                else -> sb.append(c)
            }
            return sb.toString()
        }

        private fun hasArabic(s: String): Boolean {
            for (c in s) {
                val x = c.code
                if ((x in 0x0590..0x08FF) || (x in 0xFB1D..0xFDFF) || (x in 0xFE70..0xFEFF)) return true
            }
            return false
        }

        private fun fontFamily(name: String?): String {
            if (name == null) return ""
            val n = name.lowercase()
            val serif = listOf("times", "traditional arabic", "simplified arabic", "naskh", "amiri",
                "garamond", "cambria", "georgia", "book", "palatino", "sakkal")
            val generic = if (serif.any { n.contains(it) }) "serif" else "sans-serif"
            return "font-family:'${name.replace("'", "")}',$generic;"
        }

        private fun fmt(f: Float): String = if (f == f.toInt().toFloat()) f.toInt().toString() else f.toString()

        private fun runHtml(pa: Para, text: String): String {
            if (text.isEmpty()) return ""
            val paraStyleId = pa.props.styleId ?: defaultParaStyle
            val paraR = resolve(paraStyleId).second
            val run = pa.runProps
            val charR = run?.styleId?.let { resolve(it).second }
            val eff = merge(merge(merge(defR, paraR), charR), run)
            val cs = eff.rtl == true || hasArabic(text)
            val bold = if (cs) (eff.bCs ?: eff.b) else eff.b
            val italic = if (cs) (eff.iCs ?: eff.i) else eff.i
            val size = if (cs) (eff.szCs ?: eff.sz) else eff.sz
            val font = if (cs) (eff.fontCs ?: eff.fontLatin) else (eff.fontLatin ?: eff.fontCs)
            val css = StringBuilder()
            css.append(fontFamily(font))
            if (size != null) css.append("font-size:${fmt(size)}pt;")
            if (bold == true) css.append("font-weight:bold;")
            if (italic == true) css.append("font-style:italic;")
            val deco = ArrayList<String>()
            if (eff.u == true) deco.add("underline")
            if (eff.strike == true) deco.add("line-through")
            if (deco.isNotEmpty()) css.append("text-decoration:${deco.joinToString(" ")};")
            eff.color?.let { if (it.length == 6) css.append("color:#$it;") }
            when (eff.vert) {
                "superscript" -> css.append("vertical-align:super;font-size:smaller;")
                "subscript" -> css.append("vertical-align:sub;font-size:smaller;")
            }
            val escaped = esc(text)
            return if (css.isEmpty()) escaped else "<span style=\"$css\">$escaped</span>"
        }

        private fun imageTag(pa: Para, rid: String?) {
            if (rid == null) return
            var target = rels[rid] ?: return
            target = target.removePrefix("/")
            val path = if (target.startsWith("word/")) target else "word/$target"
            val data = files[path] ?: return
            val ext = path.substringAfterLast('.').lowercase()
            val mime = when (ext) {
                "png" -> "image/png"; "jpg", "jpeg" -> "image/jpeg"
                "gif" -> "image/gif"; "bmp" -> "image/bmp"; else -> return
            }
            val w = if (pa.cx > 0) "width:${fmt(pa.cx / 12700f)}pt;" else ""
            pa.cx = 0
            pa.buf.append("<img style=\"${w}max-width:100%;height:auto\" src=\"data:$mime;base64,")
                .append(Base64.encodeToString(data, Base64.NO_WRAP)).append("\">")
        }

        private fun paraHtml(pa: Para): String {
            val styleId = pa.props.styleId ?: defaultParaStyle
            val (sp, sr) = resolve(styleId)
            val eff = merge(merge(defP, sp), pa.props)
            val baseR = merge(defR, sr)
            val css = StringBuilder()
            css.append("direction:").append(if (eff.bidi == true) "rtl;" else "ltr;")
            css.append("text-align:").append(when (eff.jc) {
                "center" -> "center"
                "right", "end" -> "end"
                "both", "distribute", "thaiDistribute" -> "justify"
                else -> "start"
            }).append(";")
            css.append("margin:${fmt((eff.before ?: 0) / 20f)}pt 0 ${fmt((eff.after ?: 0) / 20f)}pt 0;")
            eff.indStart?.let { css.append("margin-inline-start:${fmt(it / 20f)}pt;") }
            eff.indEnd?.let { css.append("margin-inline-end:${fmt(it / 20f)}pt;") }
            eff.first?.let { css.append("text-indent:${fmt(it / 20f)}pt;") }
            eff.hanging?.let { css.append("text-indent:-${fmt(it / 20f)}pt;") }
            val line = eff.line
            if (line != null && line > 0) {
                if (eff.lineRule == null || eff.lineRule == "auto") css.append("line-height:${line / 240f};")
                else css.append("line-height:${fmt(line / 20f)}pt;")
            }
            if (eff.pageBreakBefore == true) css.append("break-before:page;page-break-before:always;")
            css.append("font-size:${fmt(baseR.sz ?: 11f)}pt;")
            var content = pa.buf.toString()
            if (eff.numbered == true) content = "\u2022\u00A0" + content
            if (content.isEmpty()) content = "&nbsp;"
            return "<div class=\"p\" style=\"$css\">$content</div>"
        }
    }
}
