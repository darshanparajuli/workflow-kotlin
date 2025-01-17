@file:Suppress("RemoveEmptyParenthesesFromAnnotationEntry")

package com.squareup.workflow1.ui.compose

import androidx.annotation.VisibleForTesting
import androidx.annotation.VisibleForTesting.PRIVATE
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import com.squareup.workflow1.ui.AndroidViewRendering
import com.squareup.workflow1.ui.ViewEnvironment
import com.squareup.workflow1.ui.ViewFactory
import com.squareup.workflow1.ui.ViewRegistry
import com.squareup.workflow1.ui.WorkflowUiExperimentalApi
import kotlin.reflect.KClass

/**
 * Used by [WrappedWithRootIfNecessary] to ensure the [CompositionRoot] is only applied once.
 */
private val LocalHasViewFactoryRootBeenApplied = staticCompositionLocalOf { false }

/**
 * A composable function that will be used to wrap the first (highest-level) [composeViewFactory]
 * view factory in a composition. This can be used to setup any
 * [composition locals][androidx.compose.runtime.CompositionLocal] that all [composeViewFactory]
 * factories need access to, such as UI themes.
 *
 * This function will called once, to wrap the _highest-level_ [composeViewFactory] in the tree.
 * However, composition locals are propagated down to child [composeViewFactory] compositions, so
 * any locals provided here will be available in _all_ [composeViewFactory] compositions.
 */
public typealias CompositionRoot = @Composable (content: @Composable () -> Unit) -> Unit

/**
 * Convenience function for applying a [CompositionRoot] to this [ViewEnvironment]'s [ViewRegistry].
 * See [ViewRegistry.withCompositionRoot].
 */
@WorkflowUiExperimentalApi
public fun ViewEnvironment.withCompositionRoot(root: CompositionRoot): ViewEnvironment =
  this + (ViewRegistry to this[ViewRegistry].withCompositionRoot(root))

/**
 * Returns a [ViewRegistry] that ensures that any [composeViewFactory] factories registered in this
 * registry will be wrapped exactly once with a [CompositionRoot] wrapper.
 * See [CompositionRoot] for more information.
 */
@WorkflowUiExperimentalApi
public fun ViewRegistry.withCompositionRoot(root: CompositionRoot): ViewRegistry =
  mapFactories { factory ->
    @Suppress("UNCHECKED_CAST")
    (factory as? ComposeViewFactory<Any>)?.let { composeFactory ->
      @Suppress("UNCHECKED_CAST")
      composeViewFactory(composeFactory.type) { rendering, environment ->
        WrappedWithRootIfNecessary(root) {
          composeFactory.Content(rendering, environment)
        }
      }
    } ?: factory
  }

/**
 * Adds [content] to the composition, ensuring that [CompositionRoot] has been applied. Will only
 * wrap the content at the highest occurrence of this function in the composition subtree.
 */
@VisibleForTesting(otherwise = PRIVATE)
@Composable internal fun WrappedWithRootIfNecessary(
  root: CompositionRoot,
  content: @Composable () -> Unit
) {
  if (LocalHasViewFactoryRootBeenApplied.current) {
    // The only way this local can have the value true is if, somewhere above this point in the
    // composition, the else case below was hit and wrapped us in the local. Since the root
    // wrapper will have already been applied, we can just compose content directly.
    content()
  } else {
    // If the local is false, this is the first time this function has appeared in the composition
    // so far. We provide a true value for the local for everything below us, so any recursive
    // calls to this function will hit the if case above and not re-apply the wrapper.
    CompositionLocalProvider(LocalHasViewFactoryRootBeenApplied provides true) {
      root(content)
    }
  }
}

/**
 * Applies [transform] to each [ViewFactory] in this registry. Transformations are applied lazily,
 * at the time of lookup via [ViewRegistry.getFactoryFor].
 */
@WorkflowUiExperimentalApi
private fun ViewRegistry.mapFactories(
  transform: (ViewFactory<*>) -> ViewFactory<*>
): ViewRegistry = object : ViewRegistry {
  override val keys: Set<KClass<*>> get() = this@mapFactories.keys

  override fun <RenderingT : Any> getFactoryFor(
    renderingType: KClass<out RenderingT>
  ): ViewFactory<RenderingT> {
    val factoryFor =
      this@mapFactories.getFactoryFor(renderingType) ?: throw IllegalArgumentException(
        "A ${ViewFactory::class.qualifiedName} should have been registered to display " +
          "${renderingType.qualifiedName} instances, or that class should implement " +
          "${AndroidViewRendering::class.simpleName}<${renderingType.simpleName}>."
      )
    val transformedFactory = transform(factoryFor)
    check(transformedFactory.type == renderingType) {
      "Expected transform to return a ViewFactory that is compatible with $renderingType, " +
        "but got one with type ${transformedFactory.type}"
    }
    @Suppress("UNCHECKED_CAST")
    return transformedFactory as ViewFactory<RenderingT>
  }
}
