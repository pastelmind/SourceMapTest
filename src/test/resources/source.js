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

// This should throw a TypeError
((a, b) => {
  return callSomeMethod(a) + callSomeMethod(b);
})(5, 10);

// In Node.js, the stack trace looks like:
// TypeError: o.someMethod is not a function
//     at o (SourcemapTest/src/test/resources/generated.js:1:24)
//     at SourcemapTest/src/test/resources/generated.js:1:45
//     at Object.<anonymous> (SourcemapTest/src/test/resources/generated.js:1:55)
