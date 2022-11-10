/*
 * Copyright 2013-2020 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.cloud.contract.stubrunner.messaging.stream

import java.util.concurrent.TimeUnit
import java.util.function.Consumer
import java.util.function.Function
import java.util.function.Supplier

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.assertj.core.api.BDDAssertions
import org.awaitility.Awaitility
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test

import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.autoconfigure.ImportAutoConfiguration
import org.springframework.boot.test.context.SpringBootContextLoader
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.cloud.contract.spec.Contract
import org.springframework.cloud.contract.stubrunner.StubFinder
import org.springframework.cloud.contract.stubrunner.spring.AutoConfigureStubRunner
import org.springframework.cloud.contract.verifier.messaging.MessageVerifier
import org.springframework.cloud.contract.verifier.messaging.boot.AutoConfigureMessageVerifier
import org.springframework.cloud.stream.binder.test.TestChannelBinderConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.messaging.Message
import org.springframework.test.context.ContextConfiguration

/**
 * @author Marcin Grzejszczak
 */
@ContextConfiguration(classes = Config, loader = SpringBootContextLoader)
@SpringBootTest(properties = "debug=true")
@AutoConfigureStubRunner
@AutoConfigureMessageVerifier
class StreamStubRunnerSpec {

	@Autowired
	StubFinder stubFinder
	@Autowired
	MessageVerifier<Message<?>> messaging

	@Test
	void 'should download the stub and register a route for it'() {
		when:
		// tag::client_send[]
		messaging.send(new BookReturned('foo'), [sample: 'header'], 'bookStorage')
		// end::client_send[]
		then:
		Awaitility.await().untilAsserted(() -> {
			// tag::client_receive[]
			Message<?> receivedMessage = messaging.receive('returnBook')
			// end::client_receive[]
			and:
			// tag::client_receive_message[]
			assert receivedMessage != null
			assertJsons(receivedMessage.payload)
			assert receivedMessage.headers.get('BOOK-NAME') == 'foo'
			// end::client_receive_message[]
		})
	}

	@Test
	void 'should trigger a message by label'() {
		when:
		// tag::client_trigger[]
		stubFinder.trigger('return_book_1')
		// end::client_trigger[]
		then:
		Awaitility.await().untilAsserted(() -> {
			// tag::client_trigger_receive[]
			Message<?> receivedMessage = messaging.receive('returnBook')
			// end::client_trigger_receive[]
			and:
			// tag::client_trigger_message[]
			assert receivedMessage != null
			assertJsons(receivedMessage.payload)
			assert receivedMessage.headers.get('BOOK-NAME') == 'foo'
			// end::client_trigger_message[]
		});
	}

	@Test
	void 'should trigger a label for the existing groupId and artifactId'() {
		when:
		// tag::trigger_group_artifact[]
		stubFinder.trigger('org.springframework.cloud.contract.verifier.stubs:streamService', 'return_book_1')
		// end::trigger_group_artifact[]
		then:
		Message<?> receivedMessage = messaging.receive('returnBook')
		and:
		assert receivedMessage != null
		assertJsons(receivedMessage.payload)
		assert receivedMessage.headers.get('BOOK-NAME') == 'foo'
	}

	@Test
	void 'should trigger a label for the existing artifactId'() {
		when:
		// tag::trigger_artifact[]
		stubFinder.trigger('streamService', 'return_book_1')
		// end::trigger_artifact[]
		then:
		Message<?> receivedMessage = messaging.receive('returnBook')
		and:
		assert receivedMessage != null
		assertJsons(receivedMessage.payload)
		assert receivedMessage.headers.get('BOOK-NAME') == 'foo'
	}

	@Test
	void 'should throw exception when missing label is passed'() {
		when:
		BDDAssertions.thenThrownBy(() -> stubFinder.trigger('missing label')).isInstanceOf(IllegalArgumentException)
	}

	@Test
	void 'should throw exception when missing label and artifactid is passed'() {
		when:
		BDDAssertions.thenThrownBy(() -> stubFinder.trigger('some:service', 'return_book_1')).isInstanceOf(IllegalArgumentException)
	}

	@Test
	void 'should trigger messages by running all triggers'() {
		when:
		// tag::trigger_all[]
		stubFinder.trigger()
		// end::trigger_all[]
		then:
		Message<?> receivedMessage = messaging.receive('returnBook')
		and:
		assert receivedMessage != null
		assertJsons(receivedMessage.payload)
		assert receivedMessage.headers.get('BOOK-NAME') == 'foo'
	}

	@Test
	void 'should trigger a label with no output message'() {
		when:
		// tag::trigger_no_output[]
		messaging.send(new BookReturned('foo'), [sample: 'header'], 'delete')
		// end::trigger_no_output[]
	}

	@Test
	void 'should not trigger a message that does not match input'() {
		when:
		messaging.send(new BookReturned('not_matching'), [wrong: 'header_value'], 'bookStorage')
		then:
		Message<?> receivedMessage = messaging.receive('returnBook', 100, TimeUnit.MILLISECONDS)
		and:
		assert receivedMessage == null
	}

	private boolean assertJsons(Object payload) {
		String objectAsString = payload instanceof String ? payload :
				payload instanceof byte[] ? new String(payload)
						: JsonOutput.toJson(payload)
		def json = new JsonSlurper().parseText(objectAsString)
		return json.bookName == 'foo'
	}

	Contract dsl =
			// tag::sample_dsl[]
			Contract.make {
				label 'return_book_1'
				input { triggeredBy('bookReturnedTriggered()') }
				outputMessage {
					sentTo('returnBook')
					body('''{ "bookName" : "foo" }''')
					headers { header('BOOK-NAME', 'foo') }
				}
			}
	// end::sample_dsl[]

	Contract dsl2 =
			// tag::sample_dsl_2[]
			Contract.make {
				label 'return_book_2'
				input {
					messageFrom('bookStorage')
					messageBody([
							bookName: 'foo'
					])
					messageHeaders { header('sample', 'header') }
				}
				outputMessage {
					sentTo('returnBook')
					body([
							bookName: 'foo'
					])
					headers { header('BOOK-NAME', 'foo') }
				}
			}
	// end::sample_dsl_2[]

	Contract dsl3 =
			// tag::sample_dsl_3[]
			Contract.make {
				label 'delete_book'
				input {
					messageFrom('delete')
					messageBody([
							bookName: 'foo'
					])
					messageHeaders { header('sample', 'header') }
					assertThat('bookWasDeleted()')
				}
			}
	// end::sample_dsl_3[]

	@ImportAutoConfiguration(TestChannelBinderConfiguration.class)
	@Configuration
	@EnableAutoConfiguration
	protected static class Config {

		@Bean
		Function<String, String> test1() {
			return (input) -> {
				println "Test 1 [${input}]"
				return input
			}
		}

		@Bean
		Consumer<String> test2() {
			return (input) -> {
				println "Test 2 [${input}]"
			}
		}

		@Bean
		Consumer<String> test3() {
			return (input) -> {
				println "Test 3 [${input}]"
			}
		}
	}

}
