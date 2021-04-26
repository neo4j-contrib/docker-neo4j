include make-common.mk

NEO4J_BASE_IMAGE?="openjdk:11.0.11-jdk-slim"

# Use make test TESTS='<pattern>' to run specific tests
# e.g. `make test TESTS='TestCausalCluster'` or `make test TESTS='*Cluster*'`
# the value of variable is passed to the maven test property. For more info see https://maven.apache.org/surefire/maven-surefire-plugin/examples/single-test.html
# by default this is empty which means all tests will be run
TESTS?=""

all: test
.PHONY: all

test: test-enterprise test-community
.PHONY: test

test-enterprise: tmp/.image-id-enterprise
> mvn test -Dimage=$$(cat $<) -Dedition=enterprise -Dversion=$(NEO4JVERSION) -Dtest=$(TESTS)
.PHONY: test-enterprise

test-community: tmp/.image-id-community
> mvn test -Dimage=$$(cat $<) -Dedition=community -Dversion=$(NEO4JVERSION) -Dtest=$(TESTS)
.PHONY: test-community

# just build the images, don't test or package
build: tmp/.image-id-community tmp/.image-id-enterprise
.PHONY: build

# create release images and loadable images
package: package-community package-enterprise
.PHONY: package

package-community: tmp/.image-id-community out/community/.sentinel
> mkdir -p out
> docker tag $$(cat $<) neo4j:$(NEO4JVERSION)
> docker save neo4j:$(NEO4JVERSION) > out/neo4j-community-$(NEO4JVERSION)-docker-loadable.tar

package-enterprise: tmp/.image-id-enterprise out/enterprise/.sentinel
> mkdir -p out
> docker tag $$(cat $<) neo4j:$(NEO4JVERSION)-enterprise
> docker save neo4j:$(NEO4JVERSION)-enterprise > out/neo4j-enterprise-$(NEO4JVERSION)-docker-loadable.tar

# create image from local build context
tmp/.image-id-%: tmp/local-context-%/.sentinel
> mkdir -p $(@D)
> image=test/$$RANDOM
> docker build --tag=$$image \
    --build-arg="NEO4J_URI=file:///tmp/$(call tarball,$*,$(NEO4JVERSION))" \
    $(<D)
> echo -n $$image >$@
> echo "NEO4JVERSION=$(NEO4JVERSION)" > tmp/devenv-${*}.env
> echo "NEO4J_IMAGE=$$image" >> tmp/devenv-${*}.env
> echo "NEO4J_EDITION=${*}" >> tmp/devenv-${*}.env

# copy the releaseable version of the image to the output folder.
out/%/.sentinel: tmp/image-%/.sentinel
> mkdir -p $(@D)
> cp -r $(<D)/* $(@D)
> touch $@
