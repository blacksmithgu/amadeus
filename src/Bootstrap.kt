package io.meltec.amadeus

import kotlinx.html.DIV
import kotlinx.html.FlowContent
import kotlinx.html.HtmlTagMarker
import kotlinx.html.div

@HtmlTagMarker
inline fun FlowContent.container(crossinline block: DIV.() -> Unit) {
    div(classes = "container") { block() }
}

@HtmlTagMarker
inline fun FlowContent.row(crossinline block: DIV.() -> Unit) {
    div(classes = "row") { block() }
}

enum class GridBreakpoint(val suffix: String) {
    EXTRA_SMALL(""),
    SMALL("sm"),
    MEDIUM("md"),
    LARGE("lg"),
    EXTRA_LARGE("xl")
}

@HtmlTagMarker
inline fun FlowContent.col(gridBreakpoint: GridBreakpoint, size: Int, crossinline block: DIV.() -> Unit) {
    div(classes = "col-${gridBreakpoint.suffix}-$size") { block() }
}

@HtmlTagMarker
inline fun FlowContent.card(crossinline block: DIV.() -> Unit) {
    div(classes = "card") { block() }
}

@HtmlTagMarker
inline fun FlowContent.cardBody(crossinline block: DIV.() -> Unit) {
    div(classes = "card-body") { block() }
}

@HtmlTagMarker
inline fun FlowContent.cardHeader(crossinline block: DIV.() -> Unit) {
    div(classes = "card-header") { block() }
}
