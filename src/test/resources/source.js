/**
 * @file This is the uncompressed JavaScript code.
 * It intentionally throws a TypeError.
 */

/**
 * Returns `a.someMethod()`.
 */
function callSomeMethod(a) {
  return a.someMethod();
}

/**
 * Returns `a.someMethod() + b.someMethod()`
 */
function doSomething(a, b) {
  return callSomeMethod(a) + callSomeMethod(b);
}

// This should throw a TypeError
doSomething(5, 10);
// In Node.js, the stack trace looks like:
// TypeError: a.someMethod is not a function
//     at callSomeMethod (SourcemapTest/src/test/resources/source.js:10:12)
//     at doSomething (SourcemapTest/src/test/resources/source.js:17:10)
//     at Object.<anonymous> (SourcemapTest/src/test/resources/source.js:21:1)
