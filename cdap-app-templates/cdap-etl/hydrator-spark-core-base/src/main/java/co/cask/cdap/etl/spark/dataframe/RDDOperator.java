package co.cask.cdap.etl.spark.dataframe;

import org.apache.spark.storage.StorageLevel;

public class RDDOperator implements CDatasetOperator {

    @Override
    public CDataset persist(CDataset cDataset, StorageLevel storageLevel) {
        return null;
    }
}
