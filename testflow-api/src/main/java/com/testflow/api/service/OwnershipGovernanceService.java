package com.testflow.api.service;

import com.testflow.api.dto.OwnershipDtos;
import com.testflow.api.security.CurrentAccount;
import com.testflow.api.web.*;
import com.testflow.persistence.mapper.ProjectMapper;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class OwnershipGovernanceService {
  private final CurrentAccount current;
  private final ProjectMapper projects;
  private final ProjectOwnership ownership;
  private final GovernanceTransactions transactions;

  public OwnershipGovernanceService(
      CurrentAccount current,
      ProjectMapper projects,
      ProjectOwnership ownership,
      GovernanceTransactions transactions) {
    this.current = current;
    this.projects = projects;
    this.ownership = ownership;
    this.transactions = transactions;
  }

  public PageResult<OwnershipDtos.Project> list(int page, int size) {
    current.admin();
    int offset = PageResult.offset(page, size);
    return new PageResult<>(
        projects.governanceList(offset, size).stream().map(ownership::summary).toList(),
        page,
        size,
        projects.governanceCount());
  }

  public OwnershipDtos.Detail get(long id) {
    current.admin();
    return ownership.detail(id);
  }

  public OwnershipDtos.Detail change(long id, OwnershipDtos.Change input) {
    current.admin();
    long successor = ServiceSupport.id(input.successorUserId());
    Long from = input.fromUserId() == null ? null : ServiceSupport.id(input.fromUserId());
    var ids = new ArrayList<Long>();
    ids.add(successor);
    if (from != null) ids.add(from);
    return transactions.execute(
        ids,
        false,
        () -> {
          var actor = current.admin();
          var p = projects.lockById(id);
          if (p == null) throw ApiException.missing();
          if (p.getVersion() != input.version()) throw ApiException.conflict();
          String reason = input.reason().trim();
          if (reason.isEmpty()) throw ApiException.invalid("请填写治理原因");
          ownership.transfer(p, from, successor, actor.getId(), reason);
          return ownership.detail(id);
        });
  }
}
