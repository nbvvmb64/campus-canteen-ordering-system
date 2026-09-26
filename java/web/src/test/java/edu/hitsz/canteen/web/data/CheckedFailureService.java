package edu.hitsz.canteen.web.data;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;

@Service
public class CheckedFailureService {
    private final DbStore db;
    public CheckedFailureService(DbStore db) { this.db=db; }

    @Transactional(rollbackFor = Exception.class)
    public void failAfterStockUpdate(String dishId) throws IOException {
        db.gate();
        db.jdbc().update("UPDATE dishes SET stock=stock+1 WHERE dish_id=?",dishId);
        throw new IOException("injected checked failure");
    }
}
