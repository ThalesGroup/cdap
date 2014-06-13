package com.continuuity.explore.service;

import com.google.common.base.Objects;

import java.util.List;

/**
 * Defines the rows returned by {@link ExploreService}.
 */
public class Row {
  private final List<Object> columns;

  public Row(List<Object> columns) {
    this.columns = columns;
  }

  public List<Object> getColumns() {
    return columns;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }

    Row that = (Row) o;

    return Objects.equal(this.columns, that.columns);
  }

  @Override
  public int hashCode() {
    return Objects.hashCode(columns);
  }

  @Override
  public String toString() {
    return Objects.toStringHelper(this)
      .add("columns", columns)
      .toString();
  }
}
