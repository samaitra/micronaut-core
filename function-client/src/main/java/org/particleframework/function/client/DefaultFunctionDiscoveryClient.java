/*
 * Copyright 2018 original authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.particleframework.function.client;

import io.reactivex.Flowable;
import org.particleframework.core.async.publisher.Publishers;
import org.particleframework.discovery.DiscoveryClient;
import org.particleframework.discovery.ServiceInstance;
import org.particleframework.function.LocalFunctionRegistry;
import org.particleframework.function.client.exceptions.FunctionNotFoundException;
import org.particleframework.health.HealthStatus;
import org.reactivestreams.Publisher;

import javax.inject.Singleton;
import java.net.URI;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Default implementation of the {@link FunctionDiscoveryClient} interface
 *
 * @author graemerocher
 * @since 1.0
 */
@Singleton
public class DefaultFunctionDiscoveryClient implements FunctionDiscoveryClient {

    private final DiscoveryClient discoveryClient;
    private final Map<String, FunctionDefinition> functionDefinitionMap;

    public DefaultFunctionDiscoveryClient(DiscoveryClient discoveryClient, FunctionDefinition...definitions) {
        this.discoveryClient = discoveryClient;
        this.functionDefinitionMap = new HashMap<>(definitions.length);
        for (FunctionDefinition definition : definitions) {
            functionDefinitionMap.put(definition.getName(), definition);
        }
    }

    @Override
    public Publisher<FunctionDefinition> getFunction(String functionName) {
        if(functionDefinitionMap.containsKey(functionName)) {
            return Publishers.just(functionDefinitionMap.get(functionName));
        }
        else {
            Flowable<ServiceInstance> serviceInstanceLocator = Flowable.fromPublisher(discoveryClient.getServiceIds())
                    .flatMap(Flowable::fromIterable)
                    .flatMap(discoveryClient::getInstances)
                    .flatMap(Flowable::fromIterable)
                    .filter(instance -> {
                                boolean isAvailable = instance.getHealthStatus().equals(HealthStatus.UP);
                                return isAvailable && instance.getMetadata().names().stream()
                                        .anyMatch(k -> k.equals(LocalFunctionRegistry.FUNCTION_PREFIX + functionName));
                            }

                    ).switchIfEmpty(Flowable.error(new FunctionNotFoundException(functionName)));
            return serviceInstanceLocator.map(instance -> {
                Optional<String> uri = instance.getMetadata().get(LocalFunctionRegistry.FUNCTION_PREFIX + functionName, String.class);
                if(uri.isPresent()) {
                    URI resolvedURI = instance.getURI().resolve(uri.get());
                    return new FunctionDefinition() {

                        @Override
                        public String getName() {
                            return functionName;
                        }

                        @Override
                        public Optional<URI> getURI() {
                            return Optional.of(resolvedURI);
                        }
                    };
                }
                throw new FunctionNotFoundException(functionName);
            });
        }

    }
}
