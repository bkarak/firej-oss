.PHONY: test package verify clean

test:
	mvn -q test

package:
	mvn -q -DskipTests package

verify:
	mvn -q verify

clean:
	mvn -q clean
