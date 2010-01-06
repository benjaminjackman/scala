#!/bin/sh
rm -rf build/quick/classes/library/scala/actors/
rm -rf build/quick/classes/partest/scala/tools/partest/
rm -rf build/quick/library.complete
rm -rf build/quick/partest.complete
ant
