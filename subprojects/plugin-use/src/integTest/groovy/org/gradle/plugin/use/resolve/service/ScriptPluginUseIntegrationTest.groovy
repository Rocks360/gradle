/*
 * Copyright 2017 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.gradle.plugin.use.resolve.service

import org.gradle.integtests.fixtures.AbstractIntegrationSpec
import org.gradle.integtests.fixtures.BuildOperationsFixture
import org.gradle.test.fixtures.server.http.HttpServer
import org.gradle.test.matchers.UserAgentMatcher
import org.gradle.util.GradleVersion
import org.junit.Rule

class ScriptPluginUseIntegrationTest extends AbstractIntegrationSpec {

    @Rule
    HttpServer server = new HttpServer()

    def operations = new BuildOperationsFixture(executer, temporaryFolder)

    def setup() {
        settingsFile << "rootProject.name = 'root'"
    }

    def "project script can request script plugin from a file"() {

        given:
        file("gradle/other.gradle") << "println('Hello from the other side')"

        and:
        buildFile << """

            plugins {
                script("gradle/other.gradle")
            }

        """.stripIndent()

        when:
        succeeds "help"

        then:
        output.contains("Hello from the other side")

        and:
        operations.hasOperation("Apply plugin script 'gradle/other.gradle' to root project 'root'")
    }

    def "project script can request script plugin from a remote URL"() {

        given:
        def script = file("other.gradle") << "println('Hello from the other side')"

        and:
        server.expectUserAgent(UserAgentMatcher.matchesNameAndVersion("Gradle", GradleVersion.current().getVersion()))
        server.expectGet("/remote-script-plugin.gradle", script)
        server.start()

        and:
        buildFile << """

            plugins {
                script("http://localhost:${server.port}/remote-script-plugin.gradle")
            }

        """.stripIndent()

        when:
        succeeds "help"

        then:
        output.contains("Hello from the other side")

        and:
        operations.hasOperation("Apply plugin script 'http://localhost:${server.port}/remote-script-plugin.gradle' to root project 'root'")
    }

    def "project script can request multiple script plugins"() {

        given:
        file("gradle/hello.gradle") << "println('Hello from the other side')"
        def bye = file("gradle/bye.gradle") << "println('Bye from the other side')"

        and:
        server.expectGet("/bye.gradle", bye)
        server.start()

        and:
        buildFile << """

            plugins {
                script("gradle/hello.gradle")
                script("http://localhost:${server.port}/bye.gradle")
            }

        """.stripIndent()

        when:
        succeeds "help"

        then:
        output.contains("Hello from the other side")
        output.contains("Bye from the other side")

        and:
        operations.hasOperation("Apply plugin script 'gradle/hello.gradle' to root project 'root'")
        operations.hasOperation("Apply plugin script 'http://localhost:${server.port}/bye.gradle' to root project 'root'")
    }

    def "project scripts in a multi-projects build can request script plugins"() {

        given:
        file("gradle/hello.gradle") << "println(\"Hello from the other side of \$name\")"

        and:
        settingsFile << """

            rootProject.name = "root"
            include "a", "b"

        """.stripIndent()

        and:
        buildFile << """

            plugins {
                script("gradle/hello.gradle")
            }

        """.stripIndent()
        file("a/build.gradle") << """

            plugins {
                script("../gradle/hello.gradle")
            }

        """.stripIndent()
        file("b/build.gradle") << """

            plugins {
                script("../gradle/hello.gradle")
            }

        """.stripIndent()

        and:
        buildFile << """
        """.stripIndent()

        when:
        succeeds "help"

        then:
        output.contains("Hello from the other side of root")
        output.contains("Hello from the other side of a")
        output.contains("Hello from the other side of b")

        and:
        operations.hasOperation("Apply plugin script 'gradle/hello.gradle' to root project 'root'")
        operations.hasOperation("Apply plugin script 'gradle/hello.gradle' to project ':a'")
        operations.hasOperation("Apply plugin script 'gradle/hello.gradle' to project ':b'")
    }

    def "same script plugin requested from different paths gets applied only once"() {

        given:
        file("gradle/hello.gradle") << "println('Hello from the other side')"

        and:
        buildFile << """

            plugins {
                script("gradle/hello.gradle")
                script("gradle/../gradle/hello.gradle")
            }

        """.stripIndent()

        when:
        succeeds "help", "-q"

        then:
        output.startsWith """
            Hello from the other side
            
            Welcome to Gradle
            """.stripIndent().trim()

        and:
        operations.hasOperation("Apply plugin script 'gradle/hello.gradle' to root project 'root'")
    }

    def "same script plugin requested from different URLs gets applied for each request"() {

        given:
        def hello = file("gradle/hello.gradle") << "println('Hello from the other side')"

        and:
        server.expectGet("/hello.gradle", hello)
        server.expectGet("/greetings.gradle", hello)
        server.start()

        and:
        buildFile << """

            plugins {
                script("gradle/hello.gradle")
                script("http://localhost:${server.port}/hello.gradle")
                script("http://localhost:${server.port}/greetings.gradle")
            }

        """.stripIndent()

        when:
        succeeds "help", "-q"

        then:
        output.startsWith """
            Hello from the other side
            handling http request: GET /hello.gradle
            Hello from the other side
            handling http request: GET /greetings.gradle
            Hello from the other side
            
            Welcome to Gradle
            """.stripIndent().trim()

        and:
        operations.hasOperation("Apply plugin script 'gradle/hello.gradle' to root project 'root'")
        operations.hasOperation("Apply plugin script 'http://localhost:${server.port}/hello.gradle' to root project 'root'")
        operations.hasOperation("Apply plugin script 'http://localhost:${server.port}/greetings.gradle' to root project 'root'")
    }

    def "requested script plugin can use classpath dependencies via the buildscript block"() {

        given:
        file("gradle/other.gradle") << """

            buildscript {
                repositories { jcenter() }
                dependencies {
                    classpath("org.apache.commons:commons-lang3:3.6")
                }
            }
            
            println(org.apache.commons.lang3.StringUtils.reverse("Gradle"))

        """.stripIndent()

        and:
        buildFile << """

            plugins {
                script("gradle/other.gradle")
            }

        """.stripIndent()

        when:
        succeeds "help"

        then:
        output.contains("eldarG")

        and:
        operations.hasOperation("Apply plugin script 'gradle/other.gradle' to root project 'root'")
    }

    def "build operations for the application of script plugins requested from files display canonicalized paths relative to build root dir"() {

        given:
        file("gradle/hello.gradle") << "println('Hello from the other side')"

        and:
        buildFile << """

            plugins {
                script("gradle/../gradle/hello.gradle")
            }

        """.stripIndent()

        when:
        succeeds "help"

        then:
        operations.hasOperation("Apply plugin script 'gradle/hello.gradle' to root project 'root'")
    }

    def "reasonable error message when requested script plugin file does not exists"() {

        given:
        buildFile << """
            plugins {
                script "gradle/other.gradle"
            }
        """.stripIndent()

        when:
        fails "help"

        then:
        failureCauseContains("Could not read")
        failureCauseContains("gradle/other.gradle' as it does not exist.")

        and:
        operations.failure("Apply plugin script 'gradle/other.gradle' to root project 'root'")
    }

    def "reasonable error message when requested script plugin remote URL does not exists"() {

        given:
        server.expectGetMissing("/do-not-exists.gradle")
        server.start()

        and:
        buildFile << """
            plugins {
                script "http://localhost:${server.port}/do-not-exists.gradle"
            }
        """.stripIndent()

        when:
        fails "help"

        then:
        failureCauseContains("Could not read")
        failureCauseContains("'http://localhost:${server.port}/do-not-exists.gradle' as it does not exist.")

        and:
        operations.failure("Apply plugin script 'http://localhost:${server.port}/do-not-exists.gradle' to root project 'root'")
    }


    def "reasonable error message when the same script plugin request is done twice"() {

        given:
        file("gradle/hello.gradle") << "println('Hello from the other side')"

        and:
        buildFile << """

            plugins {
                script("gradle/hello.gradle")
                script("gradle/hello.gradle")
            }

        """.stripIndent()

        when:
        fails "help"

        then:
        failure.assertHasDescription("Script Plugin 'gradle/hello.gradle' was already requested at line 4")

        and:
        !operations.hasOperation("Apply plugin script 'gradle/hello.gradle' to root project 'root'")
    }

    def "cannot set version on script plugin requests"() {

        given:
        buildFile << """

            plugins {
                script("hello.gradle") version "2"
            }

        """.stripIndent()

        when:
        fails "help"

        then:
        failure.assertHasDescription("Could not compile build file '$buildFile'")
        errorOutput.contains("version is not supported for script plugins applied using the plugins {} script block")
    }

    def "cannot set apply false on script plugin requests"() {

        given:
        buildFile << """

            plugins {
                script("hello.gradle") apply false
            }

        """.stripIndent()

        when:
        fails "help"

        then:
        failure.assertHasDescription("Could not compile build file '$buildFile'")
        errorOutput.contains("apply false is not supported for script plugins applied using the plugins {} script block")
    }
}
