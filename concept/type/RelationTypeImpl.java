/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package grakn.client.concept.type;

import grakn.client.api.GraknTransaction;
import grakn.client.api.concept.type.RelationType;
import grakn.client.common.Label;
import grakn.client.concept.thing.RelationImpl;
import grakn.client.concept.thing.ThingImpl;
import grakn.protocol.ConceptProto;

import java.util.stream.Stream;

import static grakn.client.common.rpc.RequestBuilder.Type.RelationType.createReq;
import static grakn.client.common.rpc.RequestBuilder.Type.RelationType.getRelatesReq;
import static grakn.client.common.rpc.RequestBuilder.Type.RelationType.setRelatesReq;
import static grakn.client.common.rpc.RequestBuilder.Type.RelationType.unsetRelatesReq;

public class RelationTypeImpl extends ThingTypeImpl implements RelationType {

    RelationTypeImpl(String label, boolean isRoot) {
        super(label, isRoot);
    }

    public static RelationTypeImpl of(ConceptProto.Type typeProto) {
        return new RelationTypeImpl(typeProto.getLabel(), typeProto.getRoot());
    }

    @Override
    public RelationTypeImpl.Remote asRemote(GraknTransaction transaction) {
        return new RelationTypeImpl.Remote(transaction, getLabel(), isRoot());
    }

    @Override
    public RelationTypeImpl asRelationType() {
        return this;
    }

    public static class Remote extends ThingTypeImpl.Remote implements RelationType.Remote {

        public Remote(GraknTransaction transaction, Label label, boolean isRoot) {
            super(transaction, label, isRoot);
        }

        @Override
        public RelationTypeImpl.Remote asRemote(GraknTransaction transaction) {
            return new RelationTypeImpl.Remote(transaction, getLabel(), isRoot());
        }

        @Override
        public final RelationImpl create() {
            ConceptProto.Type.Res res = execute(createReq(getLabel()));
            return RelationImpl.of(res.getRelationTypeCreateRes().getRelation());
        }

        @Override
        public final void setSupertype(RelationType relationType) {
            super.setSupertype(relationType);
        }

        @Override
        public final RoleTypeImpl getRelates(String roleLabel) {
            ConceptProto.RelationType.GetRelatesForRoleLabel.Res res =
                    execute(getRelatesReq(getLabel(), roleLabel)).getRelationTypeGetRelatesForRoleLabelRes();
            if (res.hasRoleType()) return RoleTypeImpl.of(res.getRoleType());
            else return null;
        }

        @Override
        public final Stream<RoleTypeImpl> getRelates() {
            return stream(getRelatesReq(getLabel()))
                    .flatMap(rp -> rp.getRelationTypeGetRelatesResPart().getRolesList().stream())
                    .map(RoleTypeImpl::of);
        }

        @Override
        public final void setRelates(String roleLabel) {
            execute(setRelatesReq(getLabel(), roleLabel));
        }

        @Override
        public final void setRelates(String roleLabel, String overriddenLabel) {
            execute(setRelatesReq(getLabel(), roleLabel, overriddenLabel));
        }

        @Override
        public final void unsetRelates(String roleLabel) {
            execute(unsetRelatesReq(getLabel(), roleLabel));
        }

        @Override
        public final Stream<RelationTypeImpl> getSubtypes() {
            return super.getSubtypes().map(ThingTypeImpl::asRelationType);
        }

        @Override
        public final Stream<RelationImpl> getInstances() {
            return super.getInstances().map(ThingImpl::asRelation);
        }

        @Override
        public RelationTypeImpl.Remote asRelationType() {
            return this;
        }
    }
}
