package autonewsroller.ingest;

import autonewsroller.model.Article;
import java.util.List;

public interface NewsSource { List<Article> discover() throws Exception; }
