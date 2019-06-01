# drtest

[![Clojars Project](https://img.shields.io/clojars/v/webjure/drtest.svg)](https://clojars.org/webjure/drtest)

Declarative Reagent testing library.

The test runner can take screenshots after each action when combined with `clj-chrome-devtools` test
runner. The screenshots will contain a HUD showing test progression and what the step was.

In drtest you define your UI test as a series of steps. Steps are either user defined functions or
maps defining a `:drtest.step/type` which dispatches to a multimethod. You can also define your
own step types by simply using `defmethod` and hook it to your favorite framework.

# Pics

![drtest in action](/drtest.png?raw=true)

# Steps

Steps are user defined functions or defined as maps containing `:drtest.step/type` (required), `drtest.step/label` (optional)
and type specific keys.

## Builtin step types

| Type | Description |
| --- | --- |
| `:render` | Render the reagent `:component` to new container. |
| `:expect` | Check element with `:selector` exists. Can also check that is has `:text`, `:value` and `:attributes` present. |
| `:expect-no` | Check that no element with `:selector` exists. |
| `:expect-count` | Check that `:count` amount of elements are found with `:selector`. |
| `:click` | Simulate click event on `:element` or `:selector`. |
| `:type` | Simulate typing `:text` event on `:element` or `:selector`. If `:overwrite?` is true replaces text, otherwise appends. |
| `:wait` | Wait for `:ms` milliseconds before continuing. |
| `:wait-promise` | Wait for `:promise` to be resolved. If `:as` key is specified, the promise value is added to the context with that key. Fails if promise is rejected. |


## User defined functions as steps

You can provide a function as a test step, the function will be invoked with one argument:
the current context. The function must return either a new context map or a boolean value
describing success or failure.

Returning false from a function will fail the test and consequent steps will not run.

To provide a label for a function step, use a metadata map with `:drtest.step/label` key.