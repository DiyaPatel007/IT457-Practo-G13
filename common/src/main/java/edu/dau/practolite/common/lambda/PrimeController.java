package edu.dau.practolite.common.lambda;

import edu.dau.practolite.common.config.AppProperties;
import edu.dau.practolite.common.model.Attr;
import edu.dau.practolite.common.observability.ColdStart;
import edu.dau.practolite.common.web.ApiException;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

/**
 * Target of the SnapStart priming request. It exercises Spring MVC, Jackson and one real DynamoDB
 * call (a GetItem for a key that does not exist). It answers only while priming is in progress,
 * and API Gateway has no route to it, so it is not reachable from outside.
 */
@RestController
public class PrimeController {

    public static final String PATH = "/internal/prime";

    private final DynamoDbClient dynamo;
    private final AppProperties props;

    public PrimeController(DynamoDbClient dynamo, AppProperties props) {
        this.dynamo = dynamo;
        this.props = props;
    }

    @GetMapping(PATH)
    public Map<String, Object> prime() {
        if (!ColdStart.isPriming()) {
            throw ApiException.notFound("ROUTE_NOT_FOUND", "No such endpoint");
        }
        dynamo.getItem(b -> b.tableName(props.tables().doctors())
                .key(Map.of("doctorId", Attr.s("__priming__"))));
        return Map.of("primed", true);
    }
}
