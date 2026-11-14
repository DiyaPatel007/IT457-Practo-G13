package edu.dau.practolite.users.repo;

import static edu.dau.practolite.common.model.Attr.s;
import static edu.dau.practolite.common.model.Attr.str;

import edu.dau.practolite.common.config.AppProperties;
import edu.dau.practolite.common.model.Schema.Users;
import edu.dau.practolite.common.security.Role;
import edu.dau.practolite.common.web.ApiException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Repository;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.ReturnValue;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemResponse;

/** Access to the Users table (access pattern AP1: get profile by userId). */
@Repository
public class UserRepository {

    private final DynamoDbClient dynamo;
    private final String table;

    public UserRepository(DynamoDbClient dynamo, AppProperties props) {
        this.dynamo = dynamo;
        this.table = props.tables().users();
    }

    /** Insert only: the condition stops a second registration overwriting an existing profile. */
    public void create(UserRecord user) {
        Map<String, AttributeValue> item = new HashMap<>();
        item.put(Users.USER_ID, s(user.userId()));
        item.put(Users.EMAIL, s(user.email()));
        item.put(Users.NAME, s(user.name()));
        item.put(Users.ROLE, s(user.role().name()));
        item.put(Users.CREATED_AT, s(user.createdAt()));
        item.put(Users.UPDATED_AT, s(user.updatedAt()));
        if (user.phone() != null) {
            item.put(Users.PHONE, s(user.phone()));
        }
        dynamo.putItem(b -> b.tableName(table).item(item)
                .conditionExpression("attribute_not_exists(" + Users.USER_ID + ")"));
    }

    public Optional<UserRecord> findById(String userId) {
        Map<String, AttributeValue> item = dynamo.getItem(b -> b.tableName(table)
                .key(Map.of(Users.USER_ID, s(userId)))).item();
        return item == null || item.isEmpty() ? Optional.empty() : Optional.of(map(item));
    }

    /** Updates only the fields that are not null and returns the new item. */
    public UserRecord update(String userId, String name, String phone, String updatedAt) {
        List<String> sets = new ArrayList<>();
        Map<String, String> names = new HashMap<>();
        Map<String, AttributeValue> values = new HashMap<>();
        if (name != null) {
            sets.add("#name = :name");
            names.put("#name", Users.NAME);
            values.put(":name", s(name));
        }
        if (phone != null) {
            sets.add("#phone = :phone");
            names.put("#phone", Users.PHONE);
            values.put(":phone", s(phone));
        }
        sets.add("#updatedAt = :updatedAt");
        names.put("#updatedAt", Users.UPDATED_AT);
        values.put(":updatedAt", s(updatedAt));
        try {
            UpdateItemResponse res = dynamo.updateItem(b -> b.tableName(table)
                    .key(Map.of(Users.USER_ID, s(userId)))
                    .updateExpression("SET " + String.join(", ", sets))
                    .conditionExpression("attribute_exists(" + Users.USER_ID + ")")
                    .expressionAttributeNames(names)
                    .expressionAttributeValues(values)
                    .returnValues(ReturnValue.ALL_NEW));
            return map(res.attributes());
        } catch (ConditionalCheckFailedException e) {
            throw ApiException.notFound("USER_NOT_FOUND", "Profile not found");
        }
    }

    public void delete(String userId) {
        dynamo.deleteItem(b -> b.tableName(table).key(Map.of(Users.USER_ID, s(userId))));
    }

    private static UserRecord map(Map<String, AttributeValue> item) {
        return new UserRecord(
                str(item, Users.USER_ID),
                str(item, Users.EMAIL),
                str(item, Users.NAME),
                str(item, Users.PHONE),
                Role.valueOf(str(item, Users.ROLE)),
                str(item, Users.CREATED_AT),
                str(item, Users.UPDATED_AT));
    }
}
