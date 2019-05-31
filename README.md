# drtest

Declarative Reagent testing library.

The test runner can take screenshots after each action when combined with `clj-chrome-devtools` test
runner. The screenshots will contain a HUD showing test progression and what the step was.

In drtest you define your UI test as a series of steps. Steps are either user defined functions or
maps defining a `:drtest.step/type` which dispatches to a multimethod. You can also define your
own step types by simply using `defmethod` and hook it to your favorite framework.

# Pics

![drtest in action](/drtest.png?raw=true)

# Steps

Steps are defined as maps containing `:drtest.step/type` (required), `drtest.step/label` (optional)
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
