/**
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.streamnative.pulsar.handlers.amqp.utils;

import org.apache.pulsar.common.naming.NamespaceName;
import org.apache.pulsar.common.naming.TopicDomain;

public class TopicUtil {

    public static String getTopicName(String topicPrefix, String tenant, String namespace, String name) {
        return TopicDomain.persistent + "://"
                + tenant + "/"
                + namespace + "/"
                + topicPrefix + name;
    }

    public static String getTopicName(String topicPrefix, NamespaceName namespaceName, String name) {
        return TopicDomain.persistent + "://"
                + namespaceName.getTenant() + "/"
                + namespaceName.getLocalName() + "/"
                + topicPrefix + name;
    }
}
