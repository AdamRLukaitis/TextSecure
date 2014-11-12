/**
 * Copyright (C) 2014 Open Whisper Systems
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.whispersystems.jobqueue;

import android.content.Context;
import android.util.Log;

import org.whispersystems.jobqueue.dependencies.DependencyInjector;
import org.whispersystems.jobqueue.persistence.JobSerializer;
import org.whispersystems.jobqueue.persistence.PersistentStorage;
import org.whispersystems.jobqueue.requirements.RequirementListener;
import org.whispersystems.jobqueue.requirements.RequirementProvider;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public class JobManager implements RequirementListener {

  private final JobQueue      jobQueue           = new JobQueue();
  private final Executor      eventExecutor      = Executors.newSingleThreadExecutor();
  private final AtomicBoolean hasLoadedEncrypted = new AtomicBoolean(false);

  private final PersistentStorage         persistentStorage;
  private final List<RequirementProvider> requirementProviders;
  private final DependencyInjector        dependencyInjector;

  private JobManager(Context context, String name,
                     List<RequirementProvider> requirementProviders,
                     DependencyInjector dependencyInjector,
                     JobSerializer jobSerializer, int consumers)
  {
    this.persistentStorage    = new PersistentStorage(context, name, jobSerializer, dependencyInjector);
    this.requirementProviders = requirementProviders;
    this.dependencyInjector   = dependencyInjector;

    eventExecutor.execute(new LoadTask(null));

    if (requirementProviders != null && !requirementProviders.isEmpty()) {
      for (RequirementProvider provider : requirementProviders) {
        provider.setListener(this);
      }
    }

    for (int i=0;i<consumers;i++) {
      new JobConsumer("JobConsumer-" + i, jobQueue, persistentStorage).start();
    }
  }

  public static Builder newBuilder(Context context) {
    return new Builder(context);
  }

  public RequirementProvider getRequirementProvider(String name) {
    for (RequirementProvider provider : requirementProviders) {
      if (provider.getName().equals(name)) {
        return provider;
      }
    }

    return null;
  }

  public void setEncryptionKeys(EncryptionKeys keys) {
    if (hasLoadedEncrypted.compareAndSet(false, true)) {
      eventExecutor.execute(new LoadTask(keys));
    }
  }

  public void add(final Job job) {
    eventExecutor.execute(new Runnable() {
      @Override
      public void run() {
        try {
          if (job.isPersistent()) {
            persistentStorage.store(job);
          }

          if (dependencyInjector != null) {
            dependencyInjector.injectDependencies(job);
          }

          job.onAdded();
          jobQueue.add(job);
        } catch (IOException e) {
          Log.w("JobManager", e);
          job.onCanceled();
        }
      }
    });
  }

  @Override
  public void onRequirementStatusChanged() {
    eventExecutor.execute(new Runnable() {
      @Override
      public void run() {
        jobQueue.onRequirementStatusChanged();
      }
    });
  }

  private class LoadTask implements Runnable {

    private final EncryptionKeys keys;

    public LoadTask(EncryptionKeys keys) {
      this.keys = keys;
    }

    @Override
    public void run() {
      List<Job> pendingJobs;

      if (keys == null) pendingJobs = persistentStorage.getAllUnencrypted();
      else              pendingJobs = persistentStorage.getAllEncrypted(keys);

      jobQueue.addAll(pendingJobs);
    }
  }

  public static class Builder {
    private final Context                   context;
    private       String                    name;
    private       List<RequirementProvider> requirementProviders;
    private       DependencyInjector        dependencyInjector;
    private       JobSerializer             jobSerializer;
    private       int                       consumerThreads;

    Builder(Context context) {
      this.context         = context;
      this.consumerThreads = 5;
    }

    public Builder withName(String name) {
      this.name = name;
      return this;
    }

    public Builder withRequirementProviders(RequirementProvider... requirementProviders) {
      this.requirementProviders = Arrays.asList(requirementProviders);
      return this;
    }

    public Builder withDependencyInjector(DependencyInjector dependencyInjector) {
      this.dependencyInjector = dependencyInjector;
      return this;
    }

    public Builder withJobSerializer(JobSerializer jobSerializer) {
      this.jobSerializer = jobSerializer;
      return this;
    }

    public Builder withConsumerThreads(int consumerThreads) {
      this.consumerThreads = consumerThreads;
      return this;
    }

    public JobManager build() {
      if (name == null) {
        throw new IllegalArgumentException("You must specify a name!");
      }

      return new JobManager(context, name, requirementProviders,
                            dependencyInjector, jobSerializer,
                            consumerThreads);
    }
  }

}
