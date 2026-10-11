# Error Prone's compile-time annotation metadata mentions the javac Modifier
# enum. Android never executes those annotation-processing APIs. Keep this
# exact test-only suppression; production and all other missing classes fail.
-dontwarn javax.lang.model.element.Modifier
